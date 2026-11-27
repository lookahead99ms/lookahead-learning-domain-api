package com.lookahead.domain.service.signin;

import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.db.store.plan.StudyPlanFacts;
import com.lookahead.domain.db.store.signin.AccountMerge.CandidateFacts;
import com.lookahead.domain.db.store.signin.AccountMerge.Carrier;
import com.lookahead.domain.db.store.signin.AccountMerge.Link;
import com.lookahead.domain.db.store.signin.AccountMerge.LinkChange;
import com.lookahead.domain.util.AccountMergeRules;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.lookahead.domain.model.AccountMergeModels;
import com.lookahead.domain.client.EmailClient;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.model.AccountMergeModels.Candidate;
import com.lookahead.domain.model.AccountMergeModels.Member;
import com.lookahead.domain.model.AccountMergeModels.Merged;
import com.lookahead.domain.model.AccountMergeModels.Removed;
import com.lookahead.domain.db.store.signin.AccountMerge;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.withSettings;

/**
 * DLV-1016: the service on a store behind the AccountMerge interface, without a database: every check the
 * service makes between the store's reads, the request's three attempts, and what follows a commit. The reads and the
 * fenced commits themselves are covered against DynamoDB in AccountMergeStoreDatabaseTest.
 */
class AccountMergeServiceTest {
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000001"), OTHER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final AccountPrincipal ACTOR = new AccountPrincipal(ACTOR_ID, "learner", "Learner", true, false, "learner@example.invalid");
    private static final String TOKEN = "A".repeat(43), EMAIL = "learner@example.invalid";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    private record Sent(String to, String subject, String body) {}
    private record Recorded(String email, long emailRevision, UUID requestedBy, List<LinkChange> earlier, String digest, Instant now, Instant expires) {}
    private record Members(List<Member> members) implements AccountMerge.Snapshot {}

    /** A store that answers what the test sets and remembers what it was asked, in order. */
    private static final class FakeStore implements AccountMerge {
        final List<String> calls = new ArrayList<>();
        final List<Recorded> recorded = new ArrayList<>();
        final Map<UUID, CandidateFacts> facts = new HashMap<>();
        List<Carrier> carriers = List.of(new Carrier(ACTOR_ID, false), new Carrier(OTHER_ID, false));
        List<Member> members = List.of(member(ACTOR_ID, true, true, false, null), member(OTHER_ID, true, true, false, null));
        List<Link> links = List.of();
        Link link = new Link(EMAIL, NOW.plusSeconds(600), Sha256.hex(TOKEN), NOW.minusSeconds(60), false);
        Set<UUID> carrying = Set.of();
        int racesLost; Merged merged = new Merged(List.of());
        @Override public Instant now() { return NOW; }
        @Override public Link link(String digest) { calls.add("link"); return link != null && link.digest().equals(digest) ? link : null; }
        @Override public List<Link> links(String email) { calls.add("links"); return links; }
        @Override public long emailRevision(String email) { calls.add("emailRevision"); return 7; }
        @Override public List<Carrier> carriers(String email) { calls.add("carriers"); return carriers; }
        @Override public boolean carries(String email, UUID account) { calls.add("carries"); return carrying.contains(account); }
        @Override public Snapshot read(List<UUID> pool) { calls.add("read " + pool); return new Members(members); }
        @Override public CandidateFacts candidate(Snapshot snapshot, UUID account) { calls.add("candidate"); return facts.get(account); }
        @Override public boolean recordLink(String email, long emailRevision, UUID requestedBy, List<LinkChange> earlier, String digest, Instant now, Instant expires) {
            calls.add("recordLink");
            if (racesLost > 0) { racesLost--; return false; }
            recorded.add(new Recorded(email, emailRevision, requestedBy, List.copyOf(earlier), digest, now, expires)); return true;
        }
        @Override public Merged merge(Snapshot snapshot, Link link, long emailRevision, UUID actor, UUID keep) {
            calls.add("merge");
            assertThat(emailRevision).isEqualTo(7); assertThat(actor).isEqualTo(ACTOR_ID); assertThat(link).isSameAs(this.link);
            return merged;
        }
    }
    private static Member member(UUID id, boolean admitted, boolean enabled, boolean author, String state) {
        return new Member(id, "https://issuer.test/pool", "subject-" + id, "user-" + id, EMAIL, admitted, enabled, author, state);
    }
    private static void refused(ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, failure -> { assertThat(failure.status()).isEqualTo(status); assertThat(failure.code()).isEqualTo(code); });
    }

    private final FakeStore store = new FakeStore();
    private final List<Sent> outbox = new ArrayList<>();
    private final EmailClient email = (to, subject, body) -> outbox.add(new Sent(to, subject, body));
    private final AccountRemoval removal = mock(AccountRemoval.class,withSettings().mockMaker(MockMakers.PROXY));
    private final AccountMergeService service = new AccountMergeService(store, removal, email, "https://dev.devlookahead.com/");

    @Test void theLinkIsBuiltFromAFreshTokenRecordedByItsDigestAndSentToTheVerifiedEmail() {
        try (var log = LogCapture.start()) {
            service.request(ACTOR);
            assertThat(store.calls).containsExactly("emailRevision", "carriers", "links", "recordLink");
            var recorded = store.recorded.getFirst();
            assertThat(outbox).hasSize(1);
            String token = outbox.getFirst().body().replaceAll("(?s).*token=([A-Za-z0-9_-]{43}).*", "$1");
            assertThat(outbox.getFirst()).isEqualTo(new Sent(EMAIL, AccountMergeService.SUBJECT, AccountMergeService.body("https://dev.devlookahead.com/account/merge?token=" + token)));
            assertThat(recorded).isEqualTo(new Recorded(EMAIL, 7, ACTOR_ID, List.of(), Sha256.hex(token), NOW, NOW.plus(AccountMergeRules.LINK_LIFETIME)));
            assertThat(log.lines("merge.request")).hasSize(1);
            assertThat(LogCapture.keys(log.only("merge.request"))).containsEntry("count", 2);
        }
    }

    @Test void nothingIsSentWhenNoLinkWasMadeOrThereIsNoVerifiedEmail() {
        try (var log = LogCapture.start()) {
            // Fewer than two accounts that can take part: a test account does not count, nor does someone else's pair.
            store.carriers = List.of(new Carrier(ACTOR_ID, false), new Carrier(OTHER_ID, true));
            service.request(ACTOR);
            store.carriers = List.of(new Carrier(OTHER_ID, false), new Carrier(UUID.randomUUID(), false));
            service.request(ACTOR);
            assertThat(store.calls).doesNotContain("links", "recordLink");
            // Three links in the last hour.
            store.carriers = List.of(new Carrier(ACTOR_ID, false), new Carrier(OTHER_ID, false));
            store.links = List.of(used(NOW.minusSeconds(3599)), used(NOW.minusSeconds(120)), used(NOW.minusSeconds(60)));
            service.request(ACTOR);
            assertThat(store.calls).doesNotContain("recordLink");
            // Every attempt loses the race for the email: three attempts, each reading again.
            store.links = List.of(); store.racesLost = 3; store.calls.clear();
            service.request(ACTOR);
            assertThat(store.calls).containsExactly("emailRevision", "carriers", "links", "recordLink", "emailRevision", "carriers", "links", "recordLink", "emailRevision", "carriers", "links", "recordLink");
            service.request(new AccountPrincipal(ACTOR_ID, "learner", "Learner", true));
            assertThat(outbox).isEmpty(); assertThat(store.recorded).isEmpty();
            assertThat(log.lines("merge.request")).extracting(line -> LogCapture.keys(line).get("reason"))
                    .containsExactly("NOTHING_TO_MERGE", "NOTHING_TO_MERGE", "MERGE_REQUEST_RATE_LIMITED", "MERGE_REQUEST_CONFLICT", "NO_VERIFIED_EMAIL");
            assertThat(log.lines("merge.request")).extracting(line -> LogCapture.keys(line).get("count")).containsExactly(1, 2, null, 2, null);
        }
    }
    private static Link used(Instant created) { return new Link(EMAIL, created.plusSeconds(1800), Sha256.hex(created.toString()), created, true); }

    @Test void aLostRaceIsReadAgainAndTheSecondAttemptRecordsTheLink() {
        store.racesLost = 1;
        service.request(ACTOR);
        assertThat(store.calls).containsExactly("emailRevision", "carriers", "links", "recordLink", "emailRevision", "carriers", "links", "recordLink");
        assertThat(outbox).hasSize(1); assertThat(store.recorded).hasSize(1);
    }

    @Test void aRequestDeletesDayOldLinksReplacesTheOutstandingOneAndKeepsItsExpiry() {
        Link old = used(NOW.minus(AccountMergeRules.LINK_RETENTION).minusSeconds(1));
        Link atTheDay = used(NOW.minus(AccountMergeRules.LINK_RETENTION)); // exactly a day old is kept
        Link beforeTheHour = used(NOW.minus(AccountMergeRules.REQUEST_WINDOW)); // exactly an hour old does not count
        Link expired = new Link(EMAIL, NOW, "expired", NOW.minusSeconds(1800), false); // expiring now is not outstanding
        Link live = new Link(EMAIL, NOW.plusSeconds(900), "live", NOW.minusSeconds(900), false);
        Link alsoLive = new Link(EMAIL, NOW.plusSeconds(1200), "also-live", NOW.minusSeconds(600), false);
        store.links = List.of(old, atTheDay, beforeTheHour, expired, live);
        service.request(ACTOR);
        var recorded = store.recorded.getFirst();
        assertThat(recorded.earlier()).containsExactly(new LinkChange(old, true), new LinkChange(live, false));
        assertThat(recorded.expires()).isEqualTo(live.expiresAt());
        // Two outstanding links (possible only by a fault): both replaced, the first one's expiry kept. That makes three in the hour: the next is refused.
        store.links = List.of(expired, live, alsoLive);
        service.request(ACTOR);
        assertThat(store.recorded).hasSize(1);
        store.links = List.of(live, alsoLive);
        service.request(ACTOR);
        assertThat(store.recorded.get(1).earlier()).containsExactly(new LinkChange(live, false), new LinkChange(alsoLive, false));
        assertThat(store.recorded.get(1).expires()).isEqualTo(live.expiresAt());
    }

    @Test void aLinkThatIsMalformedUnknownUsedOrExpiredOrNotTheCallersIsInvalid() {
        refused(() -> service.merge(ACTOR, "short", ACTOR_ID), 404, "MERGE_TOKEN_INVALID");
        refused(() -> service.candidates(ACTOR, null), 404, "MERGE_TOKEN_INVALID");
        assertThat(store.calls).isEmpty();
        refused(() -> service.merge(ACTOR, "B".repeat(43), ACTOR_ID), 404, "MERGE_TOKEN_INVALID");
        store.link = new Link(EMAIL, NOW.plusSeconds(600), Sha256.hex(TOKEN), NOW, true);
        refused(() -> service.merge(ACTOR, TOKEN, ACTOR_ID), 404, "MERGE_TOKEN_INVALID");
        store.link = new Link(EMAIL, NOW, Sha256.hex(TOKEN), NOW.minusSeconds(1800), false);
        refused(() -> service.candidates(ACTOR, TOKEN), 404, "MERGE_TOKEN_INVALID");
        assertThat(store.calls).containsOnly("link");
        // A live link of an email whose accounts do not include the caller.
        store.link = new Link(EMAIL, NOW.plusSeconds(1), Sha256.hex(TOKEN), NOW, false);
        store.carriers = List.of(new Carrier(OTHER_ID, false), new Carrier(ACTOR_ID, true));
        refused(() -> service.merge(ACTOR, TOKEN, OTHER_ID), 404, "MERGE_TOKEN_INVALID");
        refused(() -> service.candidates(ACTOR, TOKEN), 404, "MERGE_TOKEN_INVALID");
        assertThat(store.calls).doesNotContain("merge", "candidate").noneMatch(call -> call.startsWith("read"));
    }

    @Test void theMergeIsCheckedBetweenTheStoresReadsAndCommittedOnce() {
        assertThat(service.merge(ACTOR, TOKEN, ACTOR_ID)).isEqualTo(new AccountMergeModels.Kept(ACTOR_ID));
        assertThat(store.calls).containsExactly("link", "emailRevision", "carriers", "read [" + ACTOR_ID + ", " + OTHER_ID + "]", "merge");
        // An account of the pool vanished between the two reads.
        store.calls.clear(); store.members = List.of(member(ACTOR_ID, true, true, false, null));
        refused(() -> service.merge(ACTOR, TOKEN, ACTOR_ID), 409, "MERGE_CONFLICT");
        store.members = List.of(member(ACTOR_ID, true, true, false, null), member(OTHER_ID, true, true, false, null));
        // The kept account must be one of the pool: none or a stranger is refused, one that carries the email but cannot take part is a conflict.
        UUID stranger = UUID.randomUUID();
        refused(() -> service.merge(ACTOR, TOKEN, null), 422, "MERGE_KEEP_NOT_CANDIDATE");
        assertThat(store.calls).doesNotContain("carries");
        refused(() -> service.merge(ACTOR, TOKEN, stranger), 422, "MERGE_KEEP_NOT_CANDIDATE");
        store.carrying = Set.of(stranger);
        refused(() -> service.merge(ACTOR, TOKEN, stranger), 409, "MERGE_CONFLICT");
        // A disabled or deleted account stops the merge; a waiting account cannot be kept; an author account never goes.
        store.members = List.of(member(ACTOR_ID, true, true, false, null), member(OTHER_ID, true, false, false, "disabled"));
        refused(() -> service.merge(ACTOR, TOKEN, ACTOR_ID), 409, "MERGE_CONFLICT");
        store.members = List.of(member(ACTOR_ID, false, true, false, null), member(OTHER_ID, true, true, false, null));
        refused(() -> service.merge(ACTOR, TOKEN, ACTOR_ID), 422, "MERGE_KEEP_NOT_CANDIDATE");
        store.members = List.of(member(ACTOR_ID, true, true, false, null), member(OTHER_ID, true, true, true, null));
        refused(() -> service.merge(ACTOR, TOKEN, ACTOR_ID), 422, "MERGE_KEEP_NOT_CANDIDATE");
        assertThat(store.calls).doesNotContain("merge");
        // The commit lost the race: one conflict, nothing after it.
        store.members = List.of(member(ACTOR_ID, true, true, false, null), member(OTHER_ID, true, true, false, null));
        store.merged = null;
        try (var log = LogCapture.start()) {
            refused(() -> service.merge(ACTOR, TOKEN, ACTOR_ID), 409, "MERGE_CONFLICT");
            assertThat(store.calls.stream().filter("merge"::equals)).hasSize(1);
            assertThat(log.lines("account.merge")).isEmpty();
            assertThat(LogCapture.keys(log.only("merge.merge"))).containsEntry("reason", "MERGE_CONFLICT");
        }
        verifyNoMoreInteractions(removal);
    }

    @Test void theCandidatesShowTheirSignInMethodsAndPlanProgressOldestAccountFirst() {
        var json = new ObjectMapper();
        var snapshot = json.readTree("{\"config\":{\"topicIds\":[\"java\"],\"days\":1},\"days\":[{\"day\":1,\"assignments\":[{\"id\":\"a1\"},{\"id\":\"a2\"}]}]}");
        var progress = json.readTree("{\"completedSessionIds\":[\"a1\"]}");
        UUID plan = UUID.randomUUID();
        var methods = List.<Map<String, Object>>of(Map.of("provider", "google", "email", EMAIL));
        store.facts.put(ACTOR_ID, new CandidateFacts("Learner", methods, NOW, NOW.plusSeconds(5), List.of(new StudyPlanFacts.Card(plan, "Half done", snapshot, progress, NOW.toString())), true));
        // An account without identity items and without a request time: its first sign-in is shown, and it sorts last.
        store.facts.put(OTHER_ID, new CandidateFacts(null, List.of(), null, null, List.of(), false));
        store.members = List.of(member(OTHER_ID, false, true, false, null), member(ACTOR_ID, true, true, false, null));
        var candidates = service.candidates(ACTOR, TOKEN);
        assertThat(candidates.email()).isEqualTo(EMAIL); assertThat(candidates.expiresAt()).isEqualTo(store.link.expiresAt());
        assertThat(candidates.candidates()).containsExactly(
            new Candidate(ACTOR_ID, "Learner", methods, true, NOW, NOW.plusSeconds(5), List.of(new AccountMergeModels.Plan(plan, "Half done", 50)), true),
            new Candidate(OTHER_ID, null, List.of(Map.of("provider", "cognito", "email", EMAIL)), false, null, null, List.of(), false));
        assertThat(store.calls).containsExactly("link", "carriers", "read [" + ACTOR_ID + ", " + OTHER_ID + "]", "candidate", "candidate");
        // A disabled account in the pool: the page is refused before any account is described.
        store.calls.clear(); store.members = List.of(member(ACTOR_ID, true, true, false, null), member(OTHER_ID, true, false, false, "disabled"));
        refused(() -> service.candidates(ACTOR, TOKEN), 409, "MERGE_CONFLICT");
        assertThat(store.calls).doesNotContain("candidate");
    }

    @Test void afterTheUnitAdmittedAccountsAreErasedFirstThenEachLineThenTheMergeLine() {
        UUID keep = ACTOR_ID, admitted = UUID.randomUUID(), waiting = UUID.randomUUID(), failing = UUID.randomUUID();
        store.merged = new Merged(List.of(new Removed(admitted, true, 2), new Removed(waiting, false, 1), new Removed(failing, true, 0)));
        doThrow(new IllegalStateException("store down")).when(removal).erase(ACTOR_ID, failing);
        try (var log = LogCapture.start()) {
            assertThat(service.merge(ACTOR, TOKEN, keep)).isEqualTo(new AccountMergeModels.Kept(keep));
            var order = inOrder(removal);
            order.verify(removal).erase(ACTOR_ID, admitted);
            order.verify(removal).erase(ACTOR_ID, failing);
            verifyNoMoreInteractions(removal);
            assertThat(log.lines("account.erasure")).hasSize(1);
            assertThat(log.lines("account.erasure").getFirst().getFormattedMessage())
                    .isEqualTo("Erasure of merged account " + failing + " did not complete (IllegalStateException); the account-erasure sweep tries again");
            assertThat(log.lines("account.merge-removed")).extracting(line -> LogCapture.keys(line).get("target.id")).containsExactly(admitted, waiting, failing);
            assertThat(log.lines("account.merge-removed")).extracting(line -> LogCapture.keys(line).get("admitted")).containsExactly(true, false, true);
            assertThat(log.lines("account.merge")).hasSize(1);
            assertThat(LogCapture.keys(log.lines("account.merge").getFirst()).get("count")).isEqualTo(3);
            // Every erasure comes before the first line, and the merge line is last.
            var actions = log.lines().stream().map(line -> (String) LogCapture.keys(line).get("event.action"))
                    .filter(action -> action != null && action.startsWith("account.")).toList();
            assertThat(actions).containsExactly("account.erasure", "account.merge-removed", "account.merge-removed", "account.merge-removed", "account.merge");
        }
    }

    @Test void aRefusedStepIsLoggedWithItsCodeAndPassedOn() {
        store.link = null;
        try (var log = LogCapture.start()) {
            refused(() -> service.candidates(ACTOR, TOKEN), 404, "MERGE_TOKEN_INVALID");
            refused(() -> service.merge(ACTOR, TOKEN, UUID.randomUUID()), 404, "MERGE_TOKEN_INVALID");
            assertThat(log.lines("merge.candidates")).extracting(line -> LogCapture.keys(line).get("reason")).containsExactly("MERGE_TOKEN_INVALID");
            assertThat(log.lines("merge.merge")).extracting(line -> LogCapture.keys(line).get("reason")).containsExactly("MERGE_TOKEN_INVALID");
            assertThat(log.only("merge.candidates").getFormattedMessage()).isEqualTo("Merge link opened refused for account " + ACTOR_ID + ": MERGE_TOKEN_INVALID");
            assertThat(log.only("merge.merge").getFormattedMessage()).isEqualTo("Merge refused for account " + ACTOR_ID + ": MERGE_TOKEN_INVALID");
        }
    }
}
