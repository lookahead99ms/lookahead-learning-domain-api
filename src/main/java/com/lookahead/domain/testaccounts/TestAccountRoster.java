package com.lookahead.domain.testaccounts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fixed synthetic namespace. The existing owner is deliberately outside maintenance. */
public final class TestAccountRoster {
    /** DLV-969 (owner, 2026-11-03): DEV test-account usernames are addresses on the owner's domain; they never sign in. */
    public static final String CLOUD_DOMAIN = "lookahead.dev";
    /**
     * DLV-969 S35: the issuer of every DEV test account's cloud_accounts row. A Cognito token never carries it, so the
     * row is reachable only through a test session.
     */
    public static final String TEST_ISSUER = "urn:lookahead:dev-test-account";
    public record Entry(String username, boolean staff) {
        public UUID accountId() {
            return UUID.nameUUIDFromBytes(("lookahead-local-test:" + username).getBytes(StandardCharsets.UTF_8));
        }
        public String email() { return username + "@lookahead.test"; }
        /** The DEV username, shown in lists and on the test-account picker. */
        public String cloudUsername() { return username + "@" + CLOUD_DOMAIN; }
        /** The scenario this entry uses: {@code learnerNN}, {@code co-author} or {@code admin}. */
        public String template() { return !staff ? username : username.startsWith("coauthor") ? "co-author" : "admin"; }
        public String firstName() { return Character.toUpperCase(username.charAt(0)) + username.substring(1); }
    }
    private TestAccountRoster() {}
    public static List<Entry> entries() {
        var result = new ArrayList<Entry>();
        for (int n = 1; n <= 27; n++) result.add(new Entry("learner%02d".formatted(n), false));
        for (int n = 1; n <= 2; n++) result.add(new Entry("coauthor%02d".formatted(n), true));
        for (int n = 1; n <= 6; n++) result.add(new Entry("admin%02d".formatted(n), true));
        return List.copyOf(result);
    }
}
