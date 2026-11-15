package com.lookahead.domain.cloud;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.AccountRemovalService;
import com.lookahead.domain.access.DynamoAccountErasure;
import com.lookahead.domain.access.MembershipFacts;
import com.lookahead.domain.access.StudyPlanFacts;
import com.lookahead.domain.access.TestAccountRegistryLookup;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Control;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Identity;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the merge on DynamoDB. The link is {@code MERGE#digest / TOKEN} (email, requester, created, expiry, used)
 * with its per-email index {@code EMAIL#email / MERGE#createdAt#digest} for the hourly limit and the one outstanding
 * link. Every unit reads first and commits one PartiQL transaction fenced on the {@code EMAIL#email / META} sentinel
 * (the key S36 linking and first contacts advance too) and on each account's control revision, so a request, a merge,
 * a first contact and the owner's Disable or Delete for the same accounts serialise: the loser reads again (a request)
 * or answers MERGE_CONFLICT (a merge), never a half merge. The merge transaction moves every identity, writes the
 * {@code merge} events, deletes a waiting account the way the cleanup does and marks an admitted one REMOVED in the
 * control library's own shape (terminal marker, erasure job); the erasure then runs after the commit and, if it is
 * interrupted, the {@code account-erasure} sweep or a retried Delete finishes it. Sign-ins end through the accounts'
 * {@code authGeneration}, the kept account's included.
 */
public class DynamoCloudMergeService extends CloudMergeService {
    static final int ATTEMPTS = 3;
    private record Link(String email, Instant expires, String digest, Instant createdAt) {}
    private final DynamoCloudAccounts accounts;
    private final DynamoDbClient client;
    private final String table;
    private final ObjectProvider<TestAccountRegistryLookup> testAccounts;
    private final ObjectProvider<MembershipFacts> memberships;
    private final ObjectProvider<StudyPlanFacts> plans;

    public DynamoCloudMergeService(DynamoDbClient client, DynamoTableNames tables, AccountRemovalService removal, EmailSender email, String publicUrl,
            ObjectProvider<TestAccountRegistryLookup> testAccounts, ObjectProvider<MembershipFacts> memberships, ObjectProvider<StudyPlanFacts> plans) {
        this(client, tables, Clock.systemUTC(), removal, email, publicUrl, testAccounts, memberships, plans);
    }
    public DynamoCloudMergeService(DynamoDbClient client, DynamoTableNames tables, Clock clock, AccountRemovalService removal, EmailSender email, String publicUrl,
            ObjectProvider<TestAccountRegistryLookup> testAccounts, ObjectProvider<MembershipFacts> memberships, ObjectProvider<StudyPlanFacts> plans) {
        super(removal, email, publicUrl);
        this.accounts = new DynamoCloudAccounts(client, tables, clock); this.client = client; this.table = tables.userAccountDetailsTableName();
        this.testAccounts = testAccounts; this.memberships = memberships; this.plans = plans;
    }

    @Override protected Requested requested(AccountPrincipal actor, String to) {
        int size = 0;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long emailRevision = accounts.emailRevision(to);
            var pool = pool(to); size = pool.size();
            if (pool.size() < 2 || !pool.contains(actor.accountId())) return new Requested(null, "NOTHING_TO_MERGE", pool.size());
            Instant now = accounts.now();
            var tx = accounts.transaction();
            int recent = 0; Instant sharedExpiry = null;
            for (var link : query(client, table, emailPk(to), "MERGE#")) {
                Instant created = instant(link, "createdAt"), expires = instant(link, "expiresAt"); String digest = text(link, "digest");
                if (created.isBefore(now.minus(Duration.ofDays(1)))) {
                    tx.delete(table, key(emailPk(to), mergeSk(created, digest)), null); tx.delete(table, key(mergePk(digest), "TOKEN"), null);
                    continue;
                }
                if (created.isAfter(now.minus(Duration.ofHours(1)))) recent++;
                if (text(link, "usedAt") == null && expires.isAfter(now)) {
                    // Replaced: the earlier link stops working, the new one keeps its expiry.
                    tx.update(table, key(emailPk(to), mergeSk(created, digest)), Map.of("usedAt", time(now)), null);
                    tx.update(table, key(mergePk(digest), "TOKEN"), Map.of("usedAt", time(now)), null);
                    if (sharedExpiry == null) sharedExpiry = expires;
                }
            }
            if (recent >= REQUESTS_PER_HOUR) return new Requested(null, "MERGE_REQUEST_RATE_LIMITED", pool.size());
            String token = randomToken(), digest = CloudSignInRegistry.digest(token);
            Instant expires = sharedExpiry != null ? sharedExpiry : now.plus(LINK_LIFETIME);
            tx.insertOnly(table, item(mergePk(digest), "TOKEN", attrs("email", s(to), "requestedBy", s(actor.accountId().toString()), "createdAt", time(now), "expiresAt", time(expires))));
            tx.insertOnly(table, item(emailPk(to), mergeSk(now, digest), attrs("digest", s(digest), "createdAt", time(now), "expiresAt", time(expires))));
            accounts.emailSentinel(tx, to, emailRevision);
            try { tx.commit(); return new Requested(linkFor(token), null, pool.size()); }
            catch (DynamoWrites.Conflict raced) { /* a request, link or merge for this email landed first: read again */ }
        }
        return new Requested(null, "MERGE_REQUEST_CONFLICT", size);
    }

    @Override protected Candidates candidatesChecked(AccountPrincipal actor, String token) {
        Link link = link(token);
        var pool = pool(link.email);
        if (!pool.contains(actor.accountId())) throw invalidToken();
        var controls = controls(pool);
        requireMergeable(controls.stream().map(DynamoCloudMergeService::member).toList());
        return new Candidates(link.email, link.expires, sorted(controls.stream().map(this::candidate).toList()));
    }

    @Override protected Kept mergeChecked(AccountPrincipal actor, String token, UUID keep) {
        Link link = link(token);
        long emailRevision = accounts.emailRevision(link.email);
        var pool = pool(link.email);
        if (!pool.contains(actor.accountId())) throw invalidToken();
        var controls = controls(pool);
        if (controls.size() != pool.size()) throw conflict();
        if (keep == null || !pool.contains(keep)) {
            if (keep != null && accounts.emailAccounts(link.email).contains(keep)) throw conflict();
            throw keepNotCandidate();
        }
        var members = controls.stream().map(DynamoCloudMergeService::member).toList();
        requireMergeable(members);
        requireKeepable(members, keep);
        Control kept = controls.stream().filter(c -> c.id().equals(keep)).findFirst().orElseThrow();
        Instant now = accounts.now(); UUID operation = UUID.randomUUID();
        var tx = accounts.transaction();
        accounts.emailSentinel(tx, link.email, emailRevision);
        tx.update(table, key(mergePk(link.digest), "TOKEN"), Map.of("usedAt", time(now)), "(\"usedAt\" IS MISSING OR \"usedAt\" IS NULL)");
        tx.update(table, key(emailPk(link.email), mergeSk(link.createdAt, link.digest)), Map.of("usedAt", time(now)), null);
        var keepCarriers = new DynamoCloudAccounts.Carriers();
        var usernames = new ArrayList<>(accounts.usernames(keep));
        if (accounts.identities(keep).isEmpty()) {
            // An imported account without identity items keeps its first sign-in as the SQL backfill does.
            var own = new Identity(UUID.randomUUID(), kept.issuer(), kept.subject(), kept.username(), CloudIdentities.provider(kept.issuer(), kept.username()), kept.verifiedEmail(), "backfill",
                    kept.requestedAt() != null ? kept.requestedAt() : now);
            if (accounts.binding(own.issuer(), own.subject()).isEmpty()) { accounts.identity(tx, keep, own); if (own.verifiedEmail() != null) keepCarriers.add(own.verifiedEmail(), own.identityId().toString()); usernames.add(own.username()); }
        }
        var lines = new ArrayList<Runnable>(); var erase = new ArrayList<UUID>(); int removed = 0;
        for (Control drop : controls) {
            if (drop.id().equals(keep)) continue;
            var identities = accounts.identities(drop.id());
            var dropCarriers = new DynamoCloudAccounts.Carriers();
            int moved = 0;
            for (Identity i : identities) {
                tx.update(table, key(identityPk(i.issuer(), i.subject()), "BINDING"), attrs("accountId", s(keep.toString()), "linkSource", s("merge")), "\"accountId\"=?", s(drop.id().toString()));
                tx.delete(table, key(account(drop.id()), identitySk(i.linkedAt(), i.identityId())), null);
                tx.insertOnly(table, item(account(keep), identitySk(i.linkedAt(), i.identityId()), attrs("identityId", s(i.identityId().toString()), "issuer", s(i.issuer()), "subject", s(i.subject()),
                        "username", s(i.username()), "provider", s(i.provider()), "verifiedEmail", s(i.verifiedEmail()), "linkSource", s("merge"), "linkedAt", time(i.linkedAt()))));
                if (i.verifiedEmail() != null) { dropCarriers.remove(i.verifiedEmail(), i.identityId().toString()); keepCarriers.add(i.verifiedEmail(), i.identityId().toString()); }
                if (!"test".equals(i.provider()) && i.username() != null) usernames.add(i.username());
                moved++;
            }
            if (identities.isEmpty() && accounts.binding(drop.issuer(), drop.subject()).isEmpty()) {
                // The SQL merge's insert of the dropped account's first sign-in under the kept account.
                var first = new Identity(UUID.randomUUID(), drop.issuer(), drop.subject(), drop.username(), CloudIdentities.provider(drop.issuer(), drop.username()), drop.verifiedEmail(), "merge", now);
                accounts.identity(tx, keep, first);
                if (first.verifiedEmail() != null) keepCarriers.add(first.verifiedEmail(), first.identityId().toString());
                usernames.add(first.username()); moved++;
            }
            accounts.merged(tx, drop.id(), drop.issuer(), drop.subject(), actor.accountId(), keep, now);
            removed++;
            int count = moved;
            if (!drop.admitted()) {
                if (drop.verifiedEmail() != null) dropCarriers.remove(drop.verifiedEmail(), "account");
                accounts.carriers(tx, dropCarriers, drop.id(), drop.issuer());
                accounts.deleteWaitingRecords(tx, drop);
                lines.add(() -> removedLine(actor.accountId(), drop.id(), keep, false, count));
                continue;
            }
            accounts.carriers(tx, dropCarriers, drop.id(), drop.issuer());
            accounts.remove(tx, drop, actor.accountId(), operation, now);
            if (get(client, table, DynamoAccountErasure.QUEUE_PARTITION, account(drop.id())).isEmpty())
                tx.insertOnly(table, item(DynamoAccountErasure.QUEUE_PARTITION, account(drop.id()), attrs("accountId", s(drop.id().toString()), "actorId", s(actor.accountId().toString()), "queuedAt", time(now))));
            erase.add(drop.id());
            lines.add(() -> removedLine(actor.accountId(), drop.id(), keep, true, count));
        }
        accounts.carriers(tx, keepCarriers, keep, kept.issuer());
        // Every sign-in of the kept account ends too (the SQL revokeSignIns): the next generation, at the read revision.
        accounts.control(tx, kept, Map.of("authGeneration", n(kept.authGeneration() + 1)), null);
        accounts.directory(tx, kept, Map.of("usernames", list(usernames.stream().map(CloudAccountItems::lower).distinct().toList())));
        try { tx.commit(); } catch (DynamoWrites.Conflict raced) { throw conflict(); }
        // Durable now: the erasure of each deleted account's data, resumable by the sweep if it stops here.
        for (UUID id : erase) {
            try { removal.erase(actor.accountId(), id); }
            catch (RuntimeException failure) {
                Events.failed(Events.EVENT, "account.erasure", "UNEXPECTED").addKeyValue("target.id", id).addKeyValue("error.type", Events.types(failure))
                    .log("Erasure of merged account {} did not complete ({}); the account-erasure sweep tries again", id, Events.types(failure));
            }
        }
        lines.forEach(Runnable::run);
        mergedLine(actor.accountId(), keep, removed);
        return new Kept(keep);
    }

    /** Accounts of {@code email} that can take part: not on the test issuer, not managed test accounts, not deleted. */
    private List<UUID> pool(String email) {
        var registry = testAccounts.getIfAvailable();
        return accounts.emailControls(email).stream().filter(c -> !c.managedTest() && (registry == null || !registry.isTestAccount(c.id()))).map(Control::id).sorted().toList();
    }
    /** The controls of the pool, in its order; one that vanished meanwhile is left out (the caller compares sizes). */
    private List<Control> controls(List<UUID> pool) {
        var controls = new ArrayList<Control>();
        for (UUID id : pool) { Control c = accounts.control(id); if (c != null) controls.add(c); }
        return controls;
    }
    private static Member member(Control c) {
        return new Member(c.id(), c.issuer(), c.subject(), c.username(), c.verifiedEmail(), c.admitted(), c.enabled(), c.author(), c.sqlState());
    }
    private Candidate candidate(Control c) {
        Instant last = query(client, table, account(c.id()), "SESSION#").stream().map(item -> instant(item, "createdAt")).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        var methods = accounts.signInMethods(c.id(), false);
        if (methods.isEmpty()) methods = List.of(method(CloudIdentities.provider(c.issuer(), c.username()), c.verifiedEmail()));
        var cards = plans.getIfAvailable();
        var studyPlans = cards == null ? List.<Plan>of() : cards.cards(c.id()).stream().map(card -> new Plan(card.id(), card.name(), percent(card.snapshot(), card.progress()))).toList();
        var facts = memberships.getIfAvailable();
        boolean paid = facts != null && facts.hasPaidMembership(c.id());
        return new Candidate(c.id(), c.displayName(), List.copyOf(methods), c.admitted(), c.requestedAt(), last, studyPlans, paid);
    }
    /** The live link of a token: present, unused, not expired. */
    private Link link(String token) {
        String digest = digest(token);
        Map<String, AttributeValue> item = get(client, table, mergePk(digest), "TOKEN");
        if (item.isEmpty() || text(item, "usedAt") != null || !instant(item, "expiresAt").isAfter(accounts.now())) throw invalidToken();
        return new Link(text(item, "email"), instant(item, "expiresAt"), digest, instant(item, "createdAt"));
    }
}
