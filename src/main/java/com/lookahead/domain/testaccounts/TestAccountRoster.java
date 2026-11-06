package com.lookahead.domain.testaccounts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixed synthetic namespace: the 27 learner scenarios, {@code learner01}–{@code learner27}. The existing owner is
 * deliberately outside maintenance.
 *
 * <p>DLV-971 (owner, 2026-11-06): the roster has learner test accounts only. The co-author and admin test accounts it
 * used to carry ({@code coauthor01}, {@code coauthor02}, {@code admin01}–{@code admin06}) are gone from the roster and
 * retired in existing databases by V21; a test account never carries a staff role any more.
 */
public final class TestAccountRoster {
    /** DLV-969 (owner, 2026-11-03): DEV test-account usernames are addresses on the owner's domain; they never sign in. */
    public static final String CLOUD_DOMAIN = "lookahead.dev";
    /**
     * DLV-969 S35: the issuer of every DEV test account's cloud_accounts row. A Cognito token never carries it, so the
     * row is reachable only through a test session.
     */
    public static final String TEST_ISSUER = "urn:lookahead:dev-test-account";
    public record Entry(String username) {
        public UUID accountId() {
            return UUID.nameUUIDFromBytes(("lookahead-local-test:" + username).getBytes(StandardCharsets.UTF_8));
        }
        public String email() { return username + "@lookahead.test"; }
        /** The DEV username, shown in lists and on the test-account picker. */
        public String cloudUsername() { return username + "@" + CLOUD_DOMAIN; }
        /** The scenario this entry uses: its own {@code learnerNN}. */
        public String template() { return username; }
        public String firstName() { return Character.toUpperCase(username.charAt(0)) + username.substring(1); }
    }
    private TestAccountRoster() {}
    public static List<Entry> entries() {
        var result = new ArrayList<Entry>();
        for (int n = 1; n <= 27; n++) result.add(new Entry("learner%02d".formatted(n)));
        return List.copyOf(result);
    }
    /** One of the 27 fixed roster accounts. */
    public static boolean isRoster(UUID id) {
        return entries().stream().anyMatch(entry -> entry.accountId().equals(id));
    }
}
