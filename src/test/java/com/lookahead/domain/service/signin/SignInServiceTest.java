package com.lookahead.domain.service.signin;

import com.lookahead.domain.util.RandomToken;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.db.store.access.StaffAccess.Assignment;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.signin.SignIn;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.SignInModels.Account;
import com.lookahead.domain.model.SignInModels.Challenge;
import com.lookahead.domain.model.SignInModels.EmailAccount;
import com.lookahead.domain.model.SignInModels.Entry;
import com.lookahead.domain.model.SignInModels.FamilyBinding;
import com.lookahead.domain.model.SignInModels.Session;
import com.lookahead.domain.model.SignInModels.StoredProfile;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.util.SignInRules;
import com.lookahead.domain.validator.AccountProfileLinkValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DLV-1016: every sign-in decision where it now lives, in SignInService, on a store that only remembers what it was
 * asked (no database). The transactions, their conditions and the three attempts of a unit are the store's and are
 * covered in SignInStoreTest and, against DynamoDB, in SignInStoreDatabaseTest.
 */
class SignInServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000007"), OTHER = UUID.fromString("00000000-0000-4000-8000-000000000008"),
            CREATED = UUID.fromString("00000000-0000-4000-8000-000000000009");
    private static final String EMAIL = "user@example.invalid";
    private final SignInIdentity identity = new SignInIdentity("https://issuer.test/pool", "subject", "family", "user", "User", NOW, EMAIL);
    private final String proof = "p".repeat(43), family = SignInRules.family(identity), binding = SignInRules.binding(proof);

    private static Account stored(UUID id, boolean admitted, boolean enabled, boolean author, boolean pending, String state, long generation) {
        return new Account(id, "https://issuer.test/pool", "subject", "user", "User", EMAIL, "cognito", admitted, enabled, author, pending, state, generation);
    }
    private static Account admitted() { return stored(ACCOUNT, true, true, false, false, "ACTIVE", 0); }
    private static Account waitingAccount(UUID id) { return stored(id, false, true, false, false, "ACTIVE", 0); }

    /** One account's stored sign-in data and the attempt on it; every read and write is remembered in order. */
    private static final class Fake implements SignIn, SignIn.FirstContact {
        final List<String> calls = new ArrayList<>();
        final List<Session> sessions = new ArrayList<>();
        final List<Challenge> challenges = new ArrayList<>();
        final Map<String, FamilyBinding> families = new HashMap<>();
        final Map<UUID, Account> byId = new HashMap<>();
        boolean known = true, owner;
        Account account = admitted();
        List<EmailAccount> carriers = List.of();
        StoredProfile profile = new StoredProfile(null, Map.of(), List.of());
        String issuedDigest; Map<String, String> savedLinks; int units;
        RuntimeException onWrite;

        @Override public <T> T unit(SignInIdentity identity, Function<Work, T> work) { units++; return work.apply(this); }
        @Override public <T> T firstContact(SignInIdentity identity, Function<FirstContact, T> attempt) { units++; return attempt.apply(this); }
        private void wrote(String call) { calls.add(call); if (onWrite != null) throw onWrite; }
        Session add(String family, String binding, Duration created, Duration active, long generation, Instant revoked) {
            var session = new Session(UUID.randomUUID(), family, binding, NOW.minus(created), NOW.minus(active), NOW.minus(active), revoked, generation, null, null);
            sessions.add(session); return session;
        }
        Challenge challenge(String digest, String family, String binding, Duration created, Instant expires, boolean cancelled, long generation, UUID selected, UUID admitted) {
            var challenge = new Challenge(digest, family, binding, NOW.minus(created), expires, NOW, cancelled, selected, admitted, generation);
            challenges.add(challenge); return challenge;
        }
        @Override public boolean known() { return known; }
        @Override public Account account() { return account; }
        @Override public Instant now() { return NOW; }
        @Override public FamilyBinding familyBinding(String family) { calls.add("familyBinding"); return families.get(family); }
        @Override public List<Session> sessions() { calls.add("sessions"); return List.copyOf(sessions); }
        @Override public Session session(UUID id) { calls.add("session"); return sessions.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null); }
        @Override public List<Challenge> challenges() { calls.add("challenges"); return List.copyOf(challenges); }
        @Override public Challenge challenge(String digest) { calls.add("challenge"); return challenges.stream().filter(c -> c.digest().equals(digest)).findFirst().orElse(null); }
        @Override public List<EmailAccount> emailAccounts(String email) { calls.add("emailAccounts " + email); return carriers; }
        @Override public StoredProfile profile() { calls.add("profile"); return profile; }
        @Override public void touch(UUID session, Instant now) { wrote("touch"); }
        @Override public UUID addSession(String family, String binding, Instant authenticatedAt, Instant now) { wrote("addSession"); return add(family, binding, Duration.ZERO, Duration.ZERO, account.authGeneration(), null).id(); }
        @Override public UUID reissueSession(UUID old, String family, String binding, Instant authenticatedAt, Instant now) { wrote("reissueSession " + old); return CREATED; }
        @Override public void issueChallenge(List<String> stale, String digest, String family, String binding, Instant authenticatedAt, Instant now, Instant expires) {
            wrote("issueChallenge stale=" + stale + " expires=" + expires); issuedDigest = digest;
        }
        @Override public UUID replaceSession(UUID selected, boolean endSelected, Challenge challenge, Instant now) { wrote("replaceSession end=" + endSelected); return CREATED; }
        @Override public void cancelChallenge(String digest) { wrote("cancelChallenge " + digest); }
        @Override public void endSessions(List<UUID> sessions, Instant now) { wrote("endSessions " + sessions.size()); }
        @Override public void endOtherSessions(UUID current) { wrote("endOtherSessions"); }
        @Override public void labelSession(UUID session, String label) {
            wrote("labelSession " + label);
            sessions.replaceAll(s -> s.id().equals(session) ? new Session(s.id(), s.family(), s.binding(), s.created(), s.active(), s.recent(), s.revoked(), s.generation(), label, s.client()) : s);
        }
        @Override public void saveDisplayName(String name) { wrote("saveDisplayName " + name); }
        @Override public void saveTheme(String theme) { wrote("saveTheme " + theme); }
        @Override public void saveLinks(Map<String, String> values) { wrote("saveLinks"); savedLinks = values; }
        @Override public void markCredentialChange() { wrote("markCredentialChange"); }
        @Override public void settleCredentialChange(boolean endSignIns, List<String> open) { wrote("settleCredentialChange " + endSignIns + " " + open); }
        @Override public boolean ownerIdentity() { calls.add("ownerIdentity"); return owner; }
        @Override public long emailRevision(String email) { calls.add("emailRevision"); return 4; }
        @Override public void link(UUID candidate, String email, long emailRevision, Instant now) { wrote("link " + candidate + " at " + emailRevision); }
        @Override public UUID create(String email, long emailRevision, boolean admitted, boolean author, Instant now) {
            wrote("create " + email + " at " + emailRevision + " admitted=" + admitted + " author=" + author);
            byId.put(CREATED, stored(CREATED, admitted, true, author, false, "ACTIVE", 0)); return CREATED;
        }
        @Override public void admitCreated(List<String> roles, Instant now) { wrote("admitCreated " + roles); byId.put(CREATED, stored(CREATED, true, true, false, false, "ACTIVE", 0)); }
        @Override public Account follow(UUID id) { calls.add("follow " + id); account = byId.get(id); return account; }
        @Override public void rebind() { calls.add("rebind"); }
        @Override public Account reload() { calls.add("reload"); account = byId.get(account.id()); return account; }
        @Override public void admitWaitingOwner(Instant now) { wrote("admitWaitingOwner"); byId.put(account.id(), stored(account.id(), true, true, true, false, "ACTIVE", 0)); }
        @Override public void refreshClaims(String email) { wrote("refreshClaims " + email); }
        @Override public void refreshEmail(String email) { wrote("refreshEmail " + email); }
        @Override public void makeAuthor() { wrote("makeAuthor"); account = stored(account.id(), true, true, true, false, "ACTIVE", 0); }
    }
    private final Fake store = new Fake();
    private SignInService signInService = new SignInService(store, null);

    private static void refused(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(status);
            assertThat(failure.code()).isEqualTo(code);
        });
    }
    /** The caller's own active sign-in. */
    private Session signedIn() { return store.add(family, binding, Duration.ofMinutes(10), Duration.ofSeconds(30), 0, null); }

    @Test void aBadLabelThemeOrLinkSetIsRefusedBeforeTheStoreIsAsked() {
        refused(() -> signInService.rename(identity, proof, null), 400, "INVALID_SIGN_IN_LABEL");
        refused(() -> signInService.rename(identity, proof, "x".repeat(81)), 400, "INVALID_SIGN_IN_LABEL");
        refused(() -> signInService.rename(identity, proof, "<b>"), 400, "INVALID_SIGN_IN_LABEL");
        refused(() -> signInService.preferences(identity, proof, null), 422, "INVALID_PREFERENCES");
        refused(() -> signInService.preferences(identity, proof, "sepia"), 422, "INVALID_PREFERENCES");
        assertThatThrownBy(() -> signInService.links(identity, proof, null)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> signInService.links(identity, proof, Map.of("github", "https://github.com/someone"))).isInstanceOf(AccountFailure.class);
        refused(() -> signInService.admit(identity, "short"), 401, "AUTHENTICATION_REQUIRED");
        assertThat(store.units).isZero();
    }

    @Test void checkedValuesAreWrittenAsCheckedAndTheLabelStripped() {
        signedIn();
        assertThat(signInService.rename(identity, proof, " Laptop ").label()).isEqualTo("Laptop");
        signInService.preferences(identity, proof, "dark");
        var sent = new LinkedHashMap<String, String>();
        for (String kind : AccountProfileLinkValidator.KINDS) sent.put(kind, null);
        sent.put("github", " https://github.com/someone ");
        signInService.links(identity, proof, sent);
        assertThat(store.calls).contains("labelSession Laptop", "saveTheme dark", "saveLinks");
        assertThat(new ArrayList<>(store.savedLinks.keySet())).isEqualTo(AccountProfileLinkValidator.KINDS);
        assertThat(store.savedLinks.get("github")).isEqualTo(AccountProfileLinkValidator.validate("github", " https://github.com/someone "));
        assertThat(store.savedLinks.get("website")).isNull();
    }

    @Test void anUnknownGoneDisabledDeletedWaitingOrHalfChangedAccountIsRefusedBeforeItsWorkStarts() {
        try (var log = LogCapture.start()) {
            store.known = false;
            refused(() -> signInService.authenticate(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
            store.known = true; store.account = null;
            refused(() -> signInService.inventory(identity, proof), 401, "AUTHENTICATION_REQUIRED");
            store.account = stored(ACCOUNT, true, false, false, false, "ACTIVE", 0);
            refused(() -> signInService.authenticate(identity, proof), 401, "AUTHENTICATION_REQUIRED");
            store.account = stored(ACCOUNT, true, true, false, false, "DISABLED", 0);
            refused(() -> signInService.terminate(identity, proof), 401, "AUTHENTICATION_REQUIRED");
            store.account = stored(ACCOUNT, true, true, false, false, "REMOVED", 0);
            refused(() -> signInService.revokeOthers(identity, proof), 401, "AUTHENTICATION_REQUIRED");
            store.account = waitingAccount(ACCOUNT);
            refused(() -> signInService.profile(identity, proof, null), 401, "AUTHENTICATION_REQUIRED");
            store.account = stored(ACCOUNT, true, true, false, true, "ACTIVE", 0);
            refused(() -> signInService.authenticate(identity, proof), 503, "CREDENTIAL_CHANGE_PENDING");
            assertThat(store.calls).isEmpty();
            assertThat(log.lines("sign-in.check")).extracting(line -> line.getFormattedMessage()).containsExactly(
                "Sign-in refused for account " + ACCOUNT + ": the owner disabled it", "Sign-in refused for account " + ACCOUNT + ": the owner disabled it",
                "Sign-in refused for account " + ACCOUNT + ": the owner deleted it", "Request refused for account " + ACCOUNT + ": a password change is not confirmed yet (503)");
            assertThat(log.lines("sign-in.check")).extracting(line -> LogCapture.keys(line).get("reason")).containsExactly("ACCOUNT_DISABLED", "ACCOUNT_DISABLED", "ACCOUNT_REMOVED", "CREDENTIAL_CHANGE_PENDING");
        }
    }

    @Test void theCallerMustHoldTheOneActiveSignInOfItsFamilyAndProofAndItIsTouchedOnceAMinute() {
        refused(() -> signInService.authenticate(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        // Idle for exactly 30 minutes, 7 days old to the second, issued under an earlier generation, ended, or another proof: none is active.
        store.add(family, binding, Duration.ofHours(1), SignInRules.IDLE, 0, null);
        store.add(family, binding, SignInRules.ABSOLUTE, Duration.ofSeconds(1), 0, null);
        store.add(family, binding, Duration.ofHours(1), Duration.ofSeconds(1), 1, null);
        store.add(family, binding, Duration.ofHours(1), Duration.ofSeconds(1), 0, NOW.minusSeconds(1));
        store.add(family, SignInRules.binding("q".repeat(43)), Duration.ofHours(1), Duration.ofSeconds(1), 0, null);
        refused(() -> signInService.authenticate(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        // One second inside both limits: active. Used 59 seconds ago: not touched; a minute ago: touched; never by a call that does not count as use.
        store.add(family, binding, SignInRules.ABSOLUTE.minusSeconds(1), Duration.ofSeconds(59), 0, null);
        assertThat(signInService.authenticate(identity, proof).accountId()).isEqualTo(ACCOUNT);
        assertThat(store.calls).doesNotContain("touch");
        store.sessions.clear(); store.calls.clear();
        store.add(family, binding, Duration.ofHours(1), SignInRules.TOUCH, 0, null);
        var principal = signInService.authenticate(identity, proof);
        assertThat(principal.username()).isEqualTo("user"); assertThat(principal.verifiedEmail()).isEqualTo(EMAIL);
        assertThat(store.calls).containsExactly("sessions", "touch");
        store.calls.clear();
        signInService.revokeOthers(identity, proof);
        assertThat(store.calls).doesNotContain("touch");
        // Two active sign-ins of one family and proof are nobody's.
        store.add(family, binding, Duration.ofHours(1), Duration.ofSeconds(1), 0, null);
        refused(() -> signInService.inventory(identity, proof), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test void aSignInTakesAFreeSlotOrItsOwnAgainAndTheThirdIsAChallenge() {
        // Authenticated five minutes ago or more: refused once the account is settled, before any sign-in is read.
        var stale = new SignInIdentity(identity.issuer(), identity.subject(), "family", "user", "User", NOW.minusSeconds(300), EMAIL);
        refused(() -> signInService.admit(stale, proof), 403, "RECENT_AUTHENTICATION_REQUIRED");
        assertThat(store.calls).containsExactly("refreshEmail " + EMAIL, "ownerIdentity");
        store.calls.clear();
        // A free slot.
        var first = signInService.admit(identity, proof);
        assertThat(first.signInId()).isNotNull(); assertThat(first.challengeToken()).isNull();
        assertThat(store.calls).containsExactly("refreshEmail " + EMAIL, "ownerIdentity", "familyBinding", "sessions", "addSession", "emailAccounts " + EMAIL);
        // The same family again is the same sign-in; with another proof, ended, or bound to another account it is refused.
        store.families.put(family, new FamilyBinding(ACCOUNT, first.signInId())); store.calls.clear();
        assertThat(signInService.admit(identity, proof).signInId()).isEqualTo(first.signInId());
        assertThat(store.calls).doesNotContain("addSession", "sessions");
        refused(() -> signInService.admit(identity, "q".repeat(43)), 401, "AUTHENTICATION_REQUIRED");
        store.families.put(family, new FamilyBinding(OTHER, first.signInId()));
        refused(() -> signInService.admit(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        store.families.put(family, new FamilyBinding(ACCOUNT, UUID.randomUUID()));
        refused(() -> signInService.admit(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        // A new family with the proof of an active sign-in replaces that sign-in.
        store.families.clear(); store.calls.clear();
        assertThat(signInService.admit(identity, proof).signInId()).isEqualTo(CREATED);
        assertThat(store.calls).contains("reissueSession " + first.signInId());
        // Two active sign-ins and a third proof: a challenge, valid for five minutes; challenges that expired before the window go with it.
        store.add("other-family", "other-binding", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null);
        store.challenge("old", "f", "b", Duration.ofHours(1), NOW.minus(SignInRules.WINDOW).minusSeconds(1), false, 0, null, null);
        store.challenge("kept", "f", "b", Duration.ofMinutes(20), NOW.minus(SignInRules.WINDOW), true, 0, null, null);
        store.calls.clear();
        var challenged = signInService.admit(identity, "r".repeat(43));
        assertThat(challenged.signInId()).isNull(); assertThat(challenged.challengeToken()).matches("[A-Za-z0-9_-]{43}"); assertThat(challenged.expiresAt()).isEqualTo(NOW.plus(SignInRules.CHALLENGE));
        assertThat(store.calls).contains("issueChallenge stale=[old] expires=" + NOW.plus(SignInRules.CHALLENGE));
        assertThat(store.issuedDigest).isEqualTo(Sha256.hex(challenged.challengeToken()));
    }

    @Test void challengesAreLimitedToOnePendingPerFamilyAndFiveInFifteenMinutes() {
        String third = SignInRules.binding("r".repeat(43));
        store.add("f1", "b1", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null); store.add("f2", "b2", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null);
        // The same family and proof already have a live challenge: no second one. A cancelled or expired one does not count.
        var pending = store.challenge("pending", family, third, Duration.ofMinutes(1), NOW.plusSeconds(1), false, 0, null, null);
        refused(() -> signInService.admit(identity, "r".repeat(43)), 409, "SIGN_IN_CHALLENGE_PENDING");
        store.challenges.clear();
        store.challenge("expired", family, third, Duration.ofMinutes(6), NOW, false, 0, null, null);
        store.challenge("cancelled", family, third, Duration.ofMinutes(1), NOW.plusSeconds(60), true, 0, null, null);
        assertThat(signInService.admit(identity, "r".repeat(43)).challengeToken()).isNotNull();
        // Five made within the window: refused and logged. One made exactly at the window's start is outside it.
        for (int made = 0; made < 3; made++) store.challenge("c" + made, "f" + made, "b", Duration.ofMinutes(2), NOW, false, 0, null, null);
        try (var log = LogCapture.start()) {
            refused(() -> signInService.admit(identity, "r".repeat(43)), 429, "SIGN_IN_RATE_LIMITED");
            var line = log.only("sign-in.challenge");
            assertThat(line.getFormattedMessage()).isEqualTo("Sign-in limit hit for account " + ACCOUNT + ": at most 5 sign-in challenges in 15 minutes; refused with 429");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "SIGN_IN_RATE_LIMITED").containsEntry("account.id", ACCOUNT);
        }
        store.challenges.removeIf(c -> c.digest().equals("c0"));
        store.challenge("edge", "f9", "b", SignInRules.WINDOW, NOW, false, 0, null, null);
        assertThat(signInService.admit(identity, "r".repeat(43)).challengeToken()).isNotNull();
        assertThat(pending.digest()).isEqualTo("pending");
    }

    @Test void aChallengeTokenIsCheckedAfterTheAccountAndMustBeTheCallersLiveChallenge() {
        String token = "t".repeat(43), digest = Sha256.hex(token);
        // No admitted account: that is the answer, whatever the token looks like.
        store.known = false;
        refused(() -> signInService.challenge(identity, proof, "bad"), 403, "ACCOUNT_ADMISSION_REQUIRED");
        store.known = true;
        refused(() -> signInService.challenge(identity, "short", token), 401, "AUTHENTICATION_REQUIRED");
        refused(() -> signInService.challenge(identity, proof, "bad"), 400, "SIGN_IN_CHALLENGE_INVALID");
        refused(() -> signInService.cancel(identity, proof, null), 400, "SIGN_IN_CHALLENGE_INVALID");
        assertThat(store.calls).isEmpty();
        refused(() -> signInService.challenge(identity, proof, token), 400, "SIGN_IN_CHALLENGE_INVALID");
        for (var dead : List.of(new Challenge(digest, family, binding, NOW, NOW.plusSeconds(60), NOW, true, null, null, 0), new Challenge(digest, family, binding, NOW, NOW, NOW, false, null, null, 0),
                new Challenge(digest, family, binding, NOW, NOW.plusSeconds(60), NOW, false, null, null, 1), new Challenge(digest, "other", binding, NOW, NOW.plusSeconds(60), NOW, false, null, null, 0),
                new Challenge(digest, family, "other", NOW, NOW.plusSeconds(60), NOW, false, null, null, 0))) {
            store.challenges.clear(); store.challenges.add(dead);
            refused(() -> signInService.challenge(identity, proof, token), 400, "SIGN_IN_CHALLENGE_INVALID");
        }
        store.challenges.clear();
        store.challenge(digest, family, binding, Duration.ofMinutes(1), NOW.plusSeconds(1), false, 0, null, null);
        store.add("f1", "b1", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null);
        var view = signInService.challenge(identity, proof, token);
        assertThat(view.limit()).isEqualTo(2); assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(view.entries()).singleElement().satisfies(entry -> assertThat(entry.current()).isFalse());
        // Cancelling writes once; a challenge that already replaced a sign-in is left as it is.
        store.calls.clear();
        signInService.cancel(identity, proof, token);
        assertThat(store.calls).containsExactly("challenge", "cancelChallenge " + digest);
        store.challenges.clear(); store.challenge(digest, family, binding, Duration.ofMinutes(1), NOW.plusSeconds(1), false, 0, OTHER, CREATED);
        store.calls.clear();
        signInService.cancel(identity, proof, token);
        assertThat(store.calls).containsExactly("challenge");
    }

    @Test void aChallengeReplacesOneChosenSignInOnce() {
        String token = "t".repeat(43), digest = Sha256.hex(token), challengeFamily = family, challengeBinding = binding;
        Session one = store.add("f1", "b1", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null), two = store.add("f2", "b2", Duration.ofMinutes(4), Duration.ofMinutes(1), 0, null);
        store.challenge(digest, challengeFamily, challengeBinding, Duration.ofMinutes(1), NOW.plusSeconds(60), false, 0, null, null);
        refused(() -> signInService.replace(identity, proof, token, null), 400, "SIGN_IN_CHALLENGE_INVALID");
        refused(() -> signInService.replace(identity, proof, token, UUID.randomUUID()), 400, "SIGN_IN_CHALLENGE_INVALID");
        try (var log = LogCapture.start()) {
            var admission = signInService.replace(identity, proof, token, one.id());
            assertThat(admission.signInId()).isEqualTo(CREATED);
            assertThat(store.calls).contains("replaceSession end=true");
            assertThat(log.only("sign-in.replaced").getFormattedMessage()).isEqualTo("Account " + ACCOUNT + " replaced sign-in " + one.id() + " with new sign-in " + CREATED);
        }
        // A sign-in that had already ended is replaced without ending it again.
        store.sessions.set(0, new Session(one.id(), "f1", "b1", one.created(), one.active(), one.recent(), NOW.minusSeconds(5), 0, null, null));
        store.calls.clear();
        signInService.replace(identity, proof, token, one.id());
        assertThat(store.calls).contains("replaceSession end=false");
        // Refused: two other sign-ins would remain; the challenge's family is already a sign-in; its proof already has one.
        Session three = store.add("f3", "b3", Duration.ofMinutes(3), Duration.ofMinutes(1), 0, null);
        refused(() -> signInService.replace(identity, proof, token, one.id()), 400, "SIGN_IN_CHALLENGE_INVALID");
        store.sessions.remove(three);
        store.families.put(challengeFamily, new FamilyBinding(ACCOUNT, UUID.randomUUID()));
        refused(() -> signInService.replace(identity, proof, token, one.id()), 400, "SIGN_IN_CHALLENGE_INVALID");
        store.families.clear();
        store.sessions.set(1, new Session(two.id(), "f2", challengeBinding, two.created(), two.active(), two.recent(), null, 0, null, null));
        refused(() -> signInService.replace(identity, proof, token, one.id()), 400, "SIGN_IN_CHALLENGE_INVALID");
        // Asked again after it was used: the same answer for the same choice (the caller must hold the sign-in it made), a refusal for another.
        store.sessions.clear(); store.challenges.clear(); store.calls.clear();
        Session made = signedIn();
        store.challenge(digest, challengeFamily, challengeBinding, Duration.ofMinutes(1), NOW.plusSeconds(60), false, 0, one.id(), made.id());
        assertThat(signInService.replace(identity, proof, token, one.id()).signInId()).isEqualTo(made.id());
        assertThat(store.calls).doesNotContain("replaceSession end=true", "replaceSession end=false");
        refused(() -> signInService.replace(identity, proof, token, two.id()), 400, "SIGN_IN_CHALLENGE_INVALID");
    }

    @Test void endingSignInsNeedsARecentAuthenticationAndSigningOutDoesNot() {
        Session mine = signedIn(), other = store.add("f1", "b1", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null);
        try (var log = LogCapture.start()) {
            refused(() -> signInService.revoke(identity, proof, UUID.randomUUID()), 400, "SIGN_IN_CHALLENGE_INVALID");
            assertThat(signInService.revoke(identity, proof, other.id())).isFalse();
            assertThat(signInService.revoke(identity, proof, mine.id())).isTrue();
            assertThat(store.calls.stream().filter("endSessions 1"::equals)).hasSize(2);
            // Already ended: nothing is written, the line and the answer are the same.
            store.sessions.set(1, new Session(other.id(), "f1", "b1", other.created(), other.active(), other.recent(), NOW.minusSeconds(1), 0, null, null));
            store.calls.clear();
            assertThat(signInService.revoke(identity, proof, other.id())).isFalse();
            assertThat(store.calls).doesNotContain("endSessions 1");
            assertThat(log.lines("sign-in.revoked")).hasSize(3);
            assertThat(log.lines("sign-in.revoked").getFirst().getFormattedMessage()).isEqualTo("Account " + ACCOUNT + " ended sign-in " + other.id());
            store.add("f2", "b2", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null);
            signInService.revokeOthers(identity, proof);
            assertThat(store.calls).contains("endOtherSessions");
            assertThat(log.only("sign-in.revoked-others").getFormattedMessage()).isEqualTo("Account " + ACCOUNT + " ended its other sign-ins (1 row(s))");
        }
        // The caller authenticated more than five minutes ago: neither is allowed; signing out is.
        store.sessions.clear(); store.calls.clear();
        store.add(family, binding, Duration.ofMinutes(10), Duration.ofMinutes(5), 0, null);
        refused(() -> signInService.revoke(identity, proof, other.id()), 403, "RECENT_AUTHENTICATION_REQUIRED");
        refused(() -> signInService.revokeOthers(identity, proof), 403, "RECENT_AUTHENTICATION_REQUIRED");
        assertThat(store.calls).doesNotContain("endSessions 1", "endOtherSessions");
        // Signing out ends every sign-in of this family and proof that is not ended yet, idle or not; with none it writes nothing and still says so.
        store.add(family, binding, Duration.ofDays(8), Duration.ofDays(8), 0, null);
        try (var log = LogCapture.start()) {
            signInService.terminate(identity, proof);
            assertThat(store.calls).contains("endSessions 2");
            store.sessions.clear(); store.calls.clear();
            signInService.terminate(identity, proof);
            assertThat(store.calls).containsExactly("sessions");
            assertThat(log.lines("sign-in.signed-out")).hasSize(2).allSatisfy(line -> assertThat(line.getFormattedMessage()).isEqualTo("Account " + ACCOUNT + " signed out"));
        }
    }

    @Test void theDisplayNameIsCheckedAfterTheSignInAndTheProfileAnswerIsBuiltFromTheStoredRecords() {
        // Without a sign-in the answer is that, not what is wrong with the name.
        refused(() -> signInService.profile(identity, proof, " "), 401, "AUTHENTICATION_REQUIRED");
        signedIn();
        for (String bad : List.of(" ", "x".repeat(161), "tab\there")) refused(() -> signInService.profile(identity, proof, bad), 422, "INVALID_PROFILE");
        assertThat(store.calls).doesNotContain("profile").noneMatch(call -> call.startsWith("saveDisplayName"));
        var storedLinks = new LinkedHashMap<String, String>();
        storedLinks.put("github", "https://github.com/someone"); storedLinks.put("retired-kind", "https://example.invalid/");
        var methods = List.<Map<String, Object>>of(Map.of("provider", "google", "email", "first@example.invalid"), Map.of("provider", "cognito"));
        store.profile = new StoredProfile(null, storedLinks, methods);
        store.calls.clear();
        var view = signInService.profile(identity, proof, " New Name ");
        assertThat(store.calls).containsExactly("sessions", "saveDisplayName New Name", "profile");
        assertThat(view).containsEntry("accountId", ACCOUNT).containsEntry("username", "user").containsEntry("displayName", "New Name").containsEntry("signInMethod", "cognito")
                .containsEntry("signInMethods", methods).containsEntry("themePreference", "system").containsEntry("email", EMAIL);
        @SuppressWarnings("unchecked") var links = (Map<String, String>) view.get("links");
        assertThat(new ArrayList<>(links.keySet())).isEqualTo(AccountProfileLinkValidator.KINDS);
        assertThat(links.get("github")).isEqualTo("https://github.com/someone");
        assertThat(links.values().stream().filter(Objects::nonNull)).hasSize(1);
        // No name given: nothing is written and the stored one is shown. Without an email of its own the account shows its first sign-in method's; a saved theme is shown.
        store.account = new Account(ACCOUNT, "https://issuer.test/pool", "subject", "user", "User", null, "google", true, true, false, false, "ACTIVE", 0);
        store.profile = new StoredProfile("dark", Map.of(), methods); store.calls.clear();
        assertThat(signInService.profile(identity, proof, null)).containsEntry("displayName", "User").containsEntry("email", "first@example.invalid").containsEntry("themePreference", "dark").containsEntry("signInMethod", "google");
        assertThat(store.calls).containsExactly("sessions", "profile");
        store.profile = new StoredProfile("light", Map.of(), List.of(Map.of("provider", "cognito")));
        assertThat(signInService.preferences(identity, proof, "light")).doesNotContainKey("email").containsEntry("themePreference", "light");
    }

    @Test void aPasswordChangeMarksTheAccountThenAsksTheSignInServiceThenEndsEverySignIn() {
        signedIn();
        store.challenge("open", "f", "b", Duration.ofMinutes(1), NOW.plusSeconds(60), false, 0, null, null);
        store.challenge("cancelled", "f", "b", Duration.ofMinutes(1), NOW.plusSeconds(60), true, 0, null, null);
        try (var log = LogCapture.start()) {
            signInService.changeCredentials(identity, proof, () -> store.calls.add("provider"));
            assertThat(store.calls).containsExactly("sessions", "markCredentialChange", "provider", "challenges", "settleCredentialChange true [open]");
            assertThat(log.lines("password.change")).hasSize(1);
            assertThat(log.lines("password.change").getFirst().getFormattedMessage())
                    .isEqualTo("Password changed for account " + ACCOUNT + "; every sign-in of the account was ended");
        }
        // The mark is cleared even while it is set: that unit alone runs on a half-changed account.
        store.account = stored(ACCOUNT, true, true, false, true, "ACTIVE", 0);
        refused(() -> signInService.changeCredentials(identity, proof, () -> {}), 503, "CREDENTIAL_CHANGE_PENDING");
    }

    @Test void aRejectedPasswordKeepsTheSignInsAndAnyOtherFailureEndsThem() {
        signedIn();
        try (var log = LogCapture.start()) {
            var wrong = new AccountFailure(400, "INVALID_CURRENT_PASSWORD", "Wrong password");
            assertThatThrownBy(() -> signInService.changeCredentials(identity, proof, () -> { throw wrong; })).isSameAs(wrong);
            assertThat(store.calls).containsExactly("sessions", "markCredentialChange", "settleCredentialChange false []");
            var unknown = new AccountFailure(503, "IDENTITY_UNAVAILABLE", "Unavailable");
            assertThatThrownBy(() -> signInService.changeCredentials(identity, proof, () -> { throw unknown; })).isSameAs(unknown);
            var crash = new IllegalStateException("lost");
            assertThatThrownBy(() -> signInService.changeCredentials(identity, proof, () -> { throw crash; })).isSameAs(crash);
            assertThat(store.calls.stream().filter("settleCredentialChange true []"::equals)).hasSize(2);
            assertThat(log.lines("password.change")).extracting(line -> LogCapture.keys(line).get("reason"))
                    .containsExactly("INVALID_CURRENT_PASSWORD", "IDENTITY_UNAVAILABLE", "UNEXPECTED");
        }
    }

    @Test void anAdmissionIsLoggedAsAdmittedWithTheMergeOfferOrAsAChallenge() {
        try (var log = LogCapture.start()) {
            // This and one other account carry the email and none is disabled: the merge is offered.
            store.carriers = List.of(new EmailAccount(ACCOUNT, true, true, true, false), new EmailAccount(OTHER, false, true, true, false));
            var admission = signInService.admit(identity, proof);
            assertThat(admission.mergeAvailable()).isTrue();
            assertThat(log.only("sign-in.admitted").getFormattedMessage())
                    .isEqualTo("Sign-in " + admission.signInId() + " admitted for account " + ACCOUNT + "; other accounts share its verified email, so the merge is offered");
            store.add("f1", "b1", Duration.ofMinutes(5), Duration.ofMinutes(1), 0, null);
            assertThat(signInService.admit(identity, "r".repeat(43)).challengeToken()).isNotNull();
            assertThat(log.only("sign-in.challenge").getFormattedMessage()).isEqualTo("Account " + ACCOUNT + " already has 2 active sign-ins: a challenge to replace one was issued");
        }
    }

    @Test void theMergeIsOfferedOnlyWhenAnotherAccountSharesTheEmailAndNoneIsDisabled() {
        var mine = new EmailAccount(ACCOUNT, true, true, true, false);
        for (var notOffered : List.of(List.of(mine), List.of(new EmailAccount(OTHER, true, true, true, false), new EmailAccount(CREATED, true, true, true, false)),
                List.of(mine, new EmailAccount(OTHER, true, false, true, false)), List.of(mine, new EmailAccount(OTHER, true, true, false, false)))) {
            store.sessions.clear(); store.carriers = notOffered;
            assertThat(signInService.admit(identity, proof).mergeAvailable()).isFalse();
        }
        try (var log = LogCapture.start()) {
            store.sessions.clear();
            signInService.admit(identity, proof);
            assertThat(log.only("sign-in.admitted").getFormattedMessage()).doesNotContain("merge is offered");
        }
        // No verified email on the sign-in (or one longer than an address can be): nothing is read and nothing offered.
        store.sessions.clear(); store.calls.clear(); store.carriers = List.of(mine, new EmailAccount(OTHER, true, true, true, false));
        var without = new SignInIdentity(identity.issuer(), identity.subject(), "family", "user", "User", NOW, null);
        assertThat(signInService.admit(without, proof).mergeAvailable()).isFalse();
        assertThat(store.calls).noneMatch(call -> call.startsWith("emailAccounts"));
    }

    @Test void anUnknownIdentityJoinsTheOneEligibleAccountOfItsEmailOrBecomesANewWaitingAccount() {
        store.known = false; store.account = null;
        try (var log = LogCapture.start()) {
            // Nobody has the email: a new waiting account, the 403, and the line that tells the owner.
            refused(() -> signInService.admit(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
            assertThat(store.calls).containsExactly("emailRevision", "emailAccounts " + EMAIL, "ownerIdentity", "create " + EMAIL + " at 4 admitted=false author=false", "follow " + CREATED);
            assertThat(log.only("admission.waiting-created").getFormattedMessage())
                    .isEqualTo("Sign-in refused for account " + CREATED + ": not admitted yet; waiting row created (removed after 48 h unless the owner admits it)");
        }
        // One eligible account (a disabled one, a deleted one and a test account are not): the identity is linked to it; it is admitted, so the sign-in goes on without a refresh.
        store.calls.clear(); store.byId.put(ACCOUNT, admitted());
        store.carriers = List.of(new EmailAccount(ACCOUNT, true, true, true, false), new EmailAccount(UUID.randomUUID(), true, false, true, false),
                new EmailAccount(UUID.randomUUID(), true, true, false, false), new EmailAccount(UUID.randomUUID(), true, true, true, true));
        try (var log = LogCapture.start()) {
            assertThat(signInService.admit(identity, proof).accountId()).isEqualTo(ACCOUNT);
            assertThat(store.calls.subList(0, 5)).containsExactly("emailRevision", "emailAccounts " + EMAIL, "link " + ACCOUNT + " at 4", "follow " + ACCOUNT, "ownerIdentity");
            assertThat(store.calls).noneMatch(call -> call.startsWith("refresh") || call.startsWith("create"));
            assertThat(log.only("identity.linked").getFormattedMessage()).isEqualTo("New cognito sign-in linked to account " + ACCOUNT + ": its verified email matched exactly one eligible account");
        }
        // Linked to an account that is still waiting: the 403 says "linked", and nothing is refreshed.
        store.account = null; store.calls.clear(); store.byId.put(OTHER, waitingAccount(OTHER)); store.carriers = List.of(new EmailAccount(OTHER, false, true, true, false));
        try (var log = LogCapture.start()) {
            refused(() -> signInService.admit(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
            assertThat(log.only("identity.linked").getFormattedMessage()).endsWith("(still waiting for the owner to admit it)");
            assertThat(log.lines("admission.waiting-linked")).hasSize(1);
            assertThat(store.calls).noneMatch(call -> call.startsWith("refresh"));
        }
        // Several eligible accounts: not linked. One of them still waiting: a new waiting account. All admitted: the new account is admitted at once.
        store.account = null; store.calls.clear();
        store.carriers = List.of(new EmailAccount(ACCOUNT, true, true, true, false), new EmailAccount(OTHER, false, true, true, false));
        try (var log = LogCapture.start()) {
            refused(() -> signInService.admit(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
            assertThat(store.calls).contains("create " + EMAIL + " at 4 admitted=false author=false").noneMatch(call -> call.startsWith("link"));
            assertThat(log.only("identity.link-skipped").getFormattedMessage()).isEqualTo("New cognito sign-in not linked: 2 eligible accounts already share its verified email; recorded as a new waiting account");
            assertThat(LogCapture.keys(log.only("identity.link-skipped"))).containsEntry("reason", "SEVERAL_SAME_EMAIL_ACCOUNTS").containsEntry("count", 2).containsEntry("allAdmitted", false).containsEntry("provider", "cognito");
        }
        store.account = null; store.calls.clear();
        store.carriers = List.of(new EmailAccount(ACCOUNT, true, true, true, false), new EmailAccount(OTHER, true, true, true, false));
        try (var log = LogCapture.start()) {
            assertThat(signInService.admit(identity, proof).accountId()).isEqualTo(CREATED);
            assertThat(store.calls).contains("create " + EMAIL + " at 4 admitted=true author=false", "rebind", "refreshEmail " + EMAIL);
            assertThat(log.only("identity.link-skipped").getFormattedMessage()).endsWith("; all are admitted, so the sign-in is admitted as its own account");
            assertThat(log.only("admission.admitted-same-email").getFormattedMessage()).isEqualTo("Account " + CREATED + " admitted at first contact: its verified email belongs only to admitted accounts");
        }
        // No verified email: never linked, and the email is not looked up.
        store.account = null; store.calls.clear();
        var without = new SignInIdentity(identity.issuer(), identity.subject(), "family", "Google_123", "User", NOW, null);
        refused(() -> signInService.admit(without, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(store.calls).containsExactly("ownerIdentity", "create null at 0 admitted=false author=false", "follow " + CREATED);
    }

    @Test void theOwnerIdentityIsAdmittedAtItsFirstContactOrWhenItIsBoundLaterAndIsMadeAnAuthor() {
        store.owner = true;
        try (var log = LogCapture.start()) {
            // Unknown: recorded as admitted, with author access.
            store.known = false; store.account = null;
            assertThat(signInService.admit(identity, proof).accountId()).isEqualTo(CREATED);
            assertThat(store.calls).contains("create " + EMAIL + " at 4 admitted=true author=true").doesNotContain("makeAuthor", "admitWaitingOwner");
            assertThat(log.only("admission.admitted-owner").getFormattedMessage()).isEqualTo("Account " + CREATED + " admitted at first contact: its sign-in is the protected owner identity");
        }
        try (var log = LogCapture.start()) {
            // Known but still waiting (the owner binding was written after its first contact): admitted now, read again, not refreshed as a waiting account.
            store.known = true; store.account = waitingAccount(ACCOUNT); store.byId.put(ACCOUNT, waitingAccount(ACCOUNT)); store.sessions.clear(); store.calls.clear();
            assertThat(signInService.admit(identity, proof).signInId()).isNotNull();
            assertThat(store.calls.subList(0, 4)).containsExactly("ownerIdentity", "admitWaitingOwner", "reload", "refreshEmail " + EMAIL);
            assertThat(store.calls).doesNotContain("makeAuthor").noneMatch(call -> call.startsWith("refreshClaims"));
            assertThat(log.only("admission.admitted-owner").getFormattedMessage())
                    .isEqualTo("Waiting account " + ACCOUNT + " admitted: its sign-in is the protected owner identity, bound after the first contact");
        }
        // Admitted without author access: it is recorded at this sign-in. Anyone else is never asked twice and never made an author.
        store.account = admitted(); store.sessions.clear(); store.calls.clear();
        signInService.admit(identity, proof);
        assertThat(store.calls.subList(0, 3)).containsExactly("refreshEmail " + EMAIL, "ownerIdentity", "makeAuthor");
        store.owner = false; store.account = admitted(); store.sessions.clear(); store.calls.clear();
        signInService.admit(identity, proof);
        assertThat(store.calls).doesNotContain("makeAuthor");
    }

    @Test void aKnownWaitingAccountIsRefreshedFromTheSignInAndStillWaitsAndADisabledOneIsRefused() {
        store.account = waitingAccount(ACCOUNT);
        try (var log = LogCapture.start()) {
            refused(() -> signInService.admit(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
            assertThat(store.calls).containsExactly("ownerIdentity", "refreshClaims " + EMAIL);
            assertThat(log.only("admission.waiting-refreshed").getFormattedMessage())
                    .isEqualTo("Sign-in refused for account " + ACCOUNT + ": not admitted yet; waiting row refreshed (removed after 48 h unless the owner admits it)");
            assertThat(LogCapture.keys(log.only("admission.waiting-refreshed"))).containsEntry("reason", "ACCOUNT_ADMISSION_REQUIRED").containsEntry("account.id", ACCOUNT);
        }
        // An email longer than an address can be is no verified email: the refresh writes none.
        store.calls.clear();
        var tooLong = new SignInIdentity(identity.issuer(), identity.subject(), "family", "user", "User", NOW, "x".repeat(250) + "@example.invalid");
        refused(() -> signInService.admit(tooLong, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(store.calls).containsExactly("ownerIdentity", "refreshClaims null");
        // Disabled, deleted, gone, or half-way through a password change: refused before anything is written.
        store.calls.clear();
        store.account = stored(ACCOUNT, false, true, false, false, "DISABLED", 0);
        refused(() -> signInService.admit(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        store.account = stored(ACCOUNT, true, true, false, false, "REMOVED", 0);
        refused(() -> signInService.admit(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        store.account = null;
        refused(() -> signInService.admit(identity, proof), 401, "AUTHENTICATION_REQUIRED");
        assertThat(store.calls).isEmpty();
        store.account = stored(ACCOUNT, true, true, false, true, "ACTIVE", 0);
        refused(() -> signInService.admit(identity, proof), 503, "CREDENTIAL_CHANGE_PENDING");
        assertThat(store.calls).containsExactly("refreshEmail " + EMAIL, "ownerIdentity");
    }

    private static Assignment prepared(UUID id, String... roles) { return new Assignment(id, CREATED, null, Set.of(roles), Set.of(), false, 2); }

    @Test void aNewWaitingAccountIsAdmittedByPreparedAccessInsideTheAttempt() {
        var access = mock(StaffAccessService.class);
        UUID invitation = UUID.randomUUID();
        when(access.claimPrepared(CREATED, EMAIL)).thenReturn(prepared(invitation, "tester", "admin"));
        signInService = new SignInService(store, access);
        store.known = false; store.account = null;
        try (var log = LogCapture.start()) {
            assertThat(signInService.admit(identity, proof).accountId()).isEqualTo(CREATED);
            assertThat(store.calls.subList(0, 7)).containsExactly("emailRevision", "emailAccounts " + EMAIL, "ownerIdentity", "create " + EMAIL + " at 4 admitted=false author=false",
                "admitCreated [admin, tester]", "follow " + CREATED, "rebind");
            verify(access).claimPrepared(CREATED, EMAIL);
            var line = log.only("admission.admitted-by-invitation");
            assertThat(line.getFormattedMessage()).isEqualTo("Account " + CREATED + " admitted at first contact by prepared access " + invitation + ": roles [admin, tester]");
            assertThat(LogCapture.keys(line)).containsEntry("account.id", CREATED).containsEntry("target.id", invitation).containsEntry("roles", new TreeSet<>(List.of("admin", "tester")));
            assertThat(log.lines("admission.waiting-created")).isEmpty();
        }
        // Prepared access without a role admits a learner.
        store.account = null; store.sessions.clear();
        when(access.claimPrepared(CREATED, EMAIL)).thenReturn(prepared(invitation));
        try (var log = LogCapture.start()) {
            signInService.admit(identity, proof);
            assertThat(store.calls).contains("admitCreated []");
            assertThat(log.only("admission.admitted-by-invitation").getFormattedMessage()).endsWith(": roles none (learner)");
        }
    }

    @Test void nothingIsClaimedForAKnownLinkedOrAlreadyAdmittedAccountOrWithoutPreparedAccess() {
        // Without the access service every first contact waits.
        store.known = false; store.account = null;
        refused(() -> signInService.admit(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
        // Nothing prepared for this email: asked, not admitted.
        var access = mock(StaffAccessService.class);
        signInService = new SignInService(store, access);
        store.account = null;
        refused(() -> signInService.admit(identity, proof), 403, "ACCOUNT_ADMISSION_REQUIRED");
        verify(access).claimPrepared(CREATED, EMAIL);
        assertThat(store.calls).noneMatch(call -> call.startsWith("admitCreated"));
        // The owner, an email that admits, a linked identity and a known account are never asked.
        var unasked = mock(StaffAccessService.class);
        signInService = new SignInService(store, unasked);
        store.account = null; store.owner = true;
        signInService.admit(identity, proof);
        store.owner = false; store.account = null; store.sessions.clear();
        store.carriers = List.of(new EmailAccount(ACCOUNT, true, true, true, false), new EmailAccount(OTHER, true, true, true, false));
        signInService.admit(identity, proof);
        store.account = null; store.sessions.clear(); store.byId.put(ACCOUNT, admitted()); store.carriers = List.of(new EmailAccount(ACCOUNT, true, true, true, false));
        signInService.admit(identity, proof);
        store.known = true; store.account = admitted(); store.sessions.clear();
        signInService.admit(identity, proof);
        verifyNoInteractions(unasked);
    }

    @Test void aLostRaceInsideTheWorkIsLeftToTheStoresRetry() {
        var raced = new DynamoWrites.Conflict(List.of("ConditionalCheckFailed"));
        // In the claim of prepared access (two first contacts for one invitation).
        var access = mock(StaffAccessService.class);
        when(access.claimPrepared(any(), any())).thenThrow(raced);
        signInService = new SignInService(store, access);
        store.known = false; store.account = null;
        assertThatThrownBy(() -> signInService.admit(identity, proof)).isSameAs(raced);
        assertThat(store.calls).noneMatch(call -> call.startsWith("follow"));
        // In a write of a known account's unit: nothing is caught, logged or answered here.
        store.known = true; store.account = admitted(); store.onWrite = raced;
        Session mine = signedIn();
        try (var log = LogCapture.start()) {
            assertThatThrownBy(() -> signInService.revoke(identity, proof, mine.id())).isSameAs(raced);
            assertThat(log.lines("sign-in.revoked")).isEmpty();
        }
    }

    @Test void theProofFreshnessAndActivityRulesAreSignInRules() {
        refused(() -> SignInRules.binding("short"), 401, "AUTHENTICATION_REQUIRED");
        assertThat(SignInRules.binding(proof)).isEqualTo(Sha256.hex(proof));
        refused(() -> SignInRules.requireFresh(NOW.minusSeconds(301), NOW), 403, "RECENT_AUTHENTICATION_REQUIRED");
        SignInRules.requireFresh(NOW.minusSeconds(299), NOW);
        assertThat(SignInRules.email(identity)).isEqualTo(EMAIL);
        assertThat(RandomToken.next()).matches("[A-Za-z0-9_-]{43}");
        assertThat(SignInRules.SIGN_IN_LIMIT).isEqualTo(2);
        assertThat(SignInRules.WAITING_EXPIRY).isEqualTo(Duration.ofHours(48));
        assertThat(List.of(SignInRules.denied().status(), SignInRules.admissionRequired().status(), SignInRules.invalid().status())).containsExactly(401, 403, 400);
        var session = new Session(UUID.randomUUID(), "f", "b", NOW.minus(SignInRules.ABSOLUTE).plusSeconds(1), NOW.minus(SignInRules.IDLE).plusSeconds(1), NOW, null, 3, null, null);
        assertThat(SignInRules.active(session, 3, NOW)).isTrue();
        assertThat(SignInRules.active(session, 4, NOW)).isFalse();
        assertThat(SignInRules.active(session, 3, NOW.plusSeconds(1))).isFalse();
        Entry entry = new Entry(session.id(), true, null, null, session.created(), session.active());
        assertThat(entry.current()).isTrue();
    }
}
