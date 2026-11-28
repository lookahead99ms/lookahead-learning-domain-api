package com.lookahead.domain.db.store.signin;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.access.AccountErasureStore;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.plan.StudyPlanFacts;
import com.lookahead.domain.db.store.signin.AccountAggregateStore.Control;
import com.lookahead.domain.db.store.signin.AccountAggregateStore.Identity;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import com.lookahead.domain.model.AccountMergeModels.*;
import com.lookahead.domain.util.SignInIdentityRules;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the merge on DynamoDB. The link is {@code MERGE#digest / TOKEN} (email, requester,
 * created, expiry, used) with its per-email index {@code EMAIL#email / MERGE#createdAt#digest} for
 * the hourly limit and the one outstanding link. Each commit is one PartiQL transaction fenced on
 * the {@code EMAIL#email / META} sentinel (the key S36 linking and first contacts advance too) and,
 * for a merge, on each account's control revision, so a request, a merge, a first contact and the
 * owner's Disable or Delete for the same accounts serialise: the loser's commit answers that it did
 * not hold, never a half merge. The merge transaction moves every identity, writes the {@code
 * merge} events, deletes a waiting account the way the cleanup does and marks an admitted one
 * REMOVED in the control library's own shape (terminal marker, erasure job); the erasure then runs
 * after the commit (in AccountMergeService) and, if it is interrupted, the {@code account-erasure}
 * sweep or a retried Delete finishes it. Sign-ins end through the accounts' {@code authGeneration},
 * the kept account's included.
 *
 * <p>DLV-1016: the reads are separate methods and AccountMergeService makes every check between
 * them (the live link, who can take part, the rate limit, which link is replaced, who may be kept)
 * and runs the request's three attempts.
 */
public class AccountMergeStore implements AccountMerge {
  /** The controls of a pool as read; the merge transaction is conditioned on their revisions. */
  private record Read(List<Control> controls) implements Snapshot {
    @Override
    public List<Member> members() {
      return controls.stream().map(AccountMergeStore::member).toList();
    }
  }

  private final AccountAggregateStore accounts;
  private final DynamoDbClient client;
  private final String table;
  private final ObjectProvider<TestAccountRegistry> testAccounts;
  private final ObjectProvider<MembershipFacts> memberships;
  private final ObjectProvider<StudyPlanFacts> plans;

  public AccountMergeStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<MembershipFacts> memberships,
      ObjectProvider<StudyPlanFacts> plans) {
    this(client, tables, Clock.systemUTC(), testAccounts, memberships, plans);
  }

  public AccountMergeStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      Clock clock,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<MembershipFacts> memberships,
      ObjectProvider<StudyPlanFacts> plans) {
    this.accounts = new AccountAggregateStore(client, tables, clock);
    this.client = client;
    this.table = tables.userAccountDetailsTableName();
    this.testAccounts = testAccounts;
    this.memberships = memberships;
    this.plans = plans;
  }

  @Override
  public Instant now() {
    return accounts.now();
  }

  @Override
  public Link link(String digest) {
    Map<String, AttributeValue> item = get(client, table, mergePk(digest), "TOKEN");
    return item.isEmpty()
        ? null
        : new Link(
            text(item, "email"),
            instant(item, "expiresAt"),
            digest,
            instant(item, "createdAt"),
            text(item, "usedAt") != null);
  }

  @Override
  public List<Link> links(String email) {
    return query(client, table, emailPk(email), "MERGE#").stream()
        .map(item ->
            new Link(
                email,
                instant(item, "expiresAt"),
                text(item, "digest"),
                instant(item, "createdAt"),
                text(item, "usedAt") != null))
        .toList();
  }

  @Override
  public long emailRevision(String email) {
    return accounts.emailRevision(email);
  }

  @Override
  public List<Carrier> carriers(String email) {
    var registry = testAccounts.getIfAvailable();
    return accounts.emailControls(email).stream()
        .map(c ->
            new Carrier(
                c.id(), c.managedTest() || registry != null && registry.isTestAccount(c.id())))
        .toList();
  }

  @Override
  public boolean carries(String email, UUID account) {
    return accounts.emailAccounts(email).contains(account);
  }

  @Override
  public Snapshot read(List<UUID> pool) {
    return new Read(controls(pool));
  }

  @Override
  public CandidateFacts candidate(Snapshot snapshot, UUID account) {
    Control c =
        ((Read) snapshot)
            .controls.stream()
                .filter(control -> control.id().equals(account))
                .findFirst()
                .orElseThrow();
    Instant last =
        query(client, table, account(c.id()), "SESSION#").stream()
            .map(item -> instant(item, "createdAt"))
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    var methods = accounts.signInMethods(c.id(), false);
    var cards = plans.getIfAvailable();
    var studyPlans = cards == null ? List.<StudyPlanFacts.Card>of() : cards.cards(c.id());
    var facts = memberships.getIfAvailable();
    boolean paid = facts != null && facts.hasPaidMembership(c.id());
    return new CandidateFacts(c.displayName(), methods, c.requestedAt(), last, studyPlans, paid);
  }

  @Override
  public boolean recordLink(
      String to,
      long emailRevision,
      UUID requestedBy,
      List<LinkChange> earlier,
      String digest,
      Instant now,
      Instant expires) {
    var tx = accounts.transaction();
    for (LinkChange change : earlier) {
      Link link = change.link();
      if (change.delete()) {
        tx.delete(table, key(emailPk(to), mergeSk(link.createdAt(), link.digest())), null);
        tx.delete(table, key(mergePk(link.digest()), "TOKEN"), null);
        continue;
      }
      // Replaced: the earlier link stops working, the new one keeps its expiry.
      tx.update(
          table,
          key(emailPk(to), mergeSk(link.createdAt(), link.digest())),
          Map.of("usedAt", time(now)),
          null);
      tx.update(table, key(mergePk(link.digest()), "TOKEN"), Map.of("usedAt", time(now)), null);
    }
    tx.insertOnly(
        table,
        item(
            mergePk(digest),
            "TOKEN",
            attrs(
                "email",
                s(to),
                "requestedBy",
                s(requestedBy.toString()),
                "createdAt",
                time(now),
                "expiresAt",
                time(expires))));
    tx.insertOnly(
        table,
        item(
            emailPk(to),
            mergeSk(now, digest),
            attrs("digest", s(digest), "createdAt", time(now), "expiresAt", time(expires))));
    accounts.emailSentinel(tx, to, emailRevision);
    try {
      tx.commit();
      return true;
    } catch (DynamoWrites.Conflict raced) {
      return false;
    }
  }

  @Override
  public Merged merge(Snapshot snapshot, Link link, long emailRevision, UUID actor, UUID keep) {
    List<Control> controls = ((Read) snapshot).controls;
    Control kept = controls.stream().filter(c -> c.id().equals(keep)).findFirst().orElseThrow();
    Instant now = accounts.now();
    UUID operation = UUID.randomUUID();
    var tx = accounts.transaction();
    accounts.emailSentinel(tx, link.email(), emailRevision);
    tx.update(
        table,
        key(mergePk(link.digest()), "TOKEN"),
        Map.of("usedAt", time(now)),
        "(\"usedAt\" IS MISSING OR \"usedAt\" IS NULL)");
    tx.update(
        table,
        key(emailPk(link.email()), mergeSk(link.createdAt(), link.digest())),
        Map.of("usedAt", time(now)),
        null);
    var keepCarriers = new AccountAggregateStore.Carriers();
    var usernames = new ArrayList<>(accounts.usernames(keep));
    if (accounts.identities(keep).isEmpty()) {
      // An imported account without identity items keeps its first sign-in: it is recorded as an
      // identity now ("backfill").
      var own =
          new Identity(
              UUID.randomUUID(),
              kept.issuer(),
              kept.subject(),
              kept.username(),
              SignInIdentityRules.provider(kept.issuer(), kept.username()),
              kept.verifiedEmail(),
              "backfill",
              kept.requestedAt() != null ? kept.requestedAt() : now);
      if (accounts.binding(own.issuer(), own.subject()).isEmpty()) {
        accounts.identity(tx, keep, own);
        if (own.verifiedEmail() != null)
          keepCarriers.add(own.verifiedEmail(), own.identityId().toString());
        usernames.add(own.username());
      }
    }
    var removed = new ArrayList<Removed>();
    for (Control drop : controls) {
      if (drop.id().equals(keep)) continue;
      var identities = accounts.identities(drop.id());
      var dropCarriers = new AccountAggregateStore.Carriers();
      int moved = 0;
      for (Identity i : identities) {
        tx.update(
            table,
            key(identityPk(i.issuer(), i.subject()), "BINDING"),
            attrs("accountId", s(keep.toString()), "linkSource", s("merge")),
            "\"accountId\"=?",
            s(drop.id().toString()));
        tx.delete(table, key(account(drop.id()), identitySk(i.linkedAt(), i.identityId())), null);
        tx.insertOnly(
            table,
            item(
                account(keep),
                identitySk(i.linkedAt(), i.identityId()),
                attrs(
                    "identityId",
                    s(i.identityId().toString()),
                    "issuer",
                    s(i.issuer()),
                    "subject",
                    s(i.subject()),
                    "username",
                    s(i.username()),
                    "provider",
                    s(i.provider()),
                    "verifiedEmail",
                    s(i.verifiedEmail()),
                    "linkSource",
                    s("merge"),
                    "linkedAt",
                    time(i.linkedAt()))));
        if (i.verifiedEmail() != null) {
          dropCarriers.remove(i.verifiedEmail(), i.identityId().toString());
          keepCarriers.add(i.verifiedEmail(), i.identityId().toString());
        }
        if (!"test".equals(i.provider()) && i.username() != null) usernames.add(i.username());
        moved++;
      }
      if (identities.isEmpty() && accounts.binding(drop.issuer(), drop.subject()).isEmpty()) {
        // The dropped account has no identity items: its first sign-in is recorded under the kept
        // account.
        var first =
            new Identity(
                UUID.randomUUID(),
                drop.issuer(),
                drop.subject(),
                drop.username(),
                SignInIdentityRules.provider(drop.issuer(), drop.username()),
                drop.verifiedEmail(),
                "merge",
                now);
        accounts.identity(tx, keep, first);
        if (first.verifiedEmail() != null)
          keepCarriers.add(first.verifiedEmail(), first.identityId().toString());
        usernames.add(first.username());
        moved++;
      }
      accounts.merged(tx, drop.id(), drop.issuer(), drop.subject(), actor, keep, now);
      int count = moved;
      if (!drop.admitted()) {
        if (drop.verifiedEmail() != null) dropCarriers.remove(drop.verifiedEmail(), "account");
        accounts.carriers(tx, dropCarriers, drop.id(), drop.issuer());
        accounts.deleteWaitingRecords(tx, drop);
        removed.add(new Removed(drop.id(), false, count));
        continue;
      }
      accounts.carriers(tx, dropCarriers, drop.id(), drop.issuer());
      accounts.remove(tx, drop, actor, operation, now);
      if (get(client, table, AccountErasureStore.QUEUE_PARTITION, account(drop.id())).isEmpty())
        tx.insertOnly(
            table,
            item(
                AccountErasureStore.QUEUE_PARTITION,
                account(drop.id()),
                attrs(
                    "accountId",
                    s(drop.id().toString()),
                    "actorId",
                    s(actor.toString()),
                    "queuedAt",
                    time(now))));
      removed.add(new Removed(drop.id(), true, count));
    }
    accounts.carriers(tx, keepCarriers, keep, kept.issuer());
    // Every sign-in of the kept account ends too: the next generation, at the read revision.
    accounts.control(tx, kept, Map.of("authGeneration", n(kept.authGeneration() + 1)), null);
    accounts.directory(
        tx,
        kept,
        Map.of("usernames", list(usernames.stream().map(AccountItems::lower).distinct().toList())));
    try {
      tx.commit();
    } catch (DynamoWrites.Conflict raced) {
      return null;
    }
    // Durable now. The service erases each deleted account's data and writes the lines, in this
    // order.
    return new Merged(List.copyOf(removed));
  }

  /**
   * The controls of the pool, in its order; one that vanished meanwhile is left out (the caller
   * compares sizes).
   */
  private List<Control> controls(List<UUID> pool) {
    var controls = new ArrayList<Control>();
    for (UUID id : pool) {
      Control c = accounts.control(id);
      if (c != null) controls.add(c);
    }
    return controls;
  }

  private static Member member(Control c) {
    return new Member(
        c.id(),
        c.issuer(),
        c.subject(),
        c.username(),
        c.verifiedEmail(),
        c.admitted(),
        c.enabled(),
        c.author(),
        c.stateLabel());
  }
}
