package com.lookahead.domain.testaccounts;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DLV-969 S35: staff use DEV test accounts through a test session inside the DEV gate. The Gateway relays, from its own
 * session only, who opened the gate (their Cognito {@code sub}) and the test account; the Domain decides on every call.
 *
 * <ul>
 *   <li>Only with the Gateway secret (constant-time compare), and only in DEV with test accounts on.</li>
 *   <li>The gate holder must be an admitted, enabled DEV account that is not disabled or deleted. Its role (owner,
 *       co-author, admin, or learner for an account without a staff role or with only reviewer or tester) goes through
 *       {@link TestAccountTemplates#allows}, the one hierarchy (owner, 2026-11-04): a learner uses test learners only.
 *       The session then acts as the test account, so its course access is the test account's own.</li>
 *   <li>The target must be an active DEV test account (its cloud_accounts row is on the test issuer).</li>
 * </ul>
 */
public class DevTestSessions {
    public static final String SECRET_HEADER = "X-LookAhead-Gateway-Secret";
    public static final String HOLDER_HEADER = "X-LookAhead-Gate-Subject";
    public static final String ACCOUNT_HEADER = "X-LookAhead-Test-Account";
    /**
     * DLV-969 (owner, 2026-11-04): request attribute holding the gate holder's account id (a {@link UUID}, absent when
     * unknown) once a test-session request was accepted, so a DEV purchase request records who sent it.
     */
    public static final String REQUESTER_ATTRIBUTE = "com.lookahead.domain.testSession.holder";
    /**
     * One test account the gate holder may use. {@code upgradedBy} (DLV-969, owner 2026-11-04): who upgraded it since its
     * latest Reset, {@code {self, label, name?, plan, approvedAt}} ({@code name} for the owner only), or null.
     */
    public record Usable(UUID accountId, String username, String displayName, List<String> roles, String scenario,
            tools.jackson.databind.node.ObjectNode upgradedBy) {
        public Usable(UUID accountId, String username, String displayName, List<String> roles, String scenario) {
            this(accountId, username, displayName, roles, scenario, null);
        }
    }
    /** An admitted, enabled gate holder: their account and their place in the one hierarchy. */
    private record Holder(UUID accountId, String role) {}
    private static final Holder NOBODY = new Holder(null, "");
    private record Target(UUID accountId, String username, String displayName) {}
    private final JdbcTemplate jdbc;
    private final TestAccountPolicy policy;
    private final String issuer;
    private final byte[] secret;

    public DevTestSessions(JdbcTemplate jdbc, TestAccountPolicy policy, String issuer, String gatewaySecret) {
        this.jdbc = jdbc; this.policy = policy; this.issuer = issuer;
        this.secret = Objects.requireNonNull(gatewaySecret).getBytes(StandardCharsets.UTF_8);
    }
    private UpgradeMarkers markers;
    /** DLV-969 (owner, 2026-11-04): the "upgraded by" marker on the picker; without it every account shows none. */
    void configureMarkers(UpgradeMarkers markers) { this.markers = markers; }

    /** Every request is compared, including one without the header, before anything else is read. */
    public boolean fromGateway(HttpServletRequest request) {
        byte[] supplied = Objects.toString(request.getHeader(SECRET_HEADER), "").getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(supplied, secret);
    }

    /** The test accounts this gate holder may use; empty for anyone who is not admitted. */
    public List<Usable> usable(String holderSubject) {
        policy.requireCloud();
        var holder = holderOf(holderSubject);
        String role = holder.role();
        // Only the owner sees names; everyone sees their own requests as "self".
        boolean named = "owner".equals(role);
        var templates = new java.util.HashMap<UUID, String>();
        for (var entry : TestAccountRoster.entries())
            TestAccountTemplates.all().stream().filter(t -> t.id().equals(entry.template())).findFirst()
                    .ifPresent(t -> templates.put(entry.accountId(), t.label()));
        return targets(null).stream().map(target -> {
            var roles = roles(target.accountId());
            if (!TestAccountTemplates.allows(role, roles)) return null;
            var upgradedBy = markers == null ? null : markers.compact(markers.find(target.accountId()), holder.accountId(), named);
            return new Usable(target.accountId(), target.username(), target.displayName(), roles.stream().sorted().toList(),
                    templates.get(target.accountId()), upgradedBy);
        }).filter(Objects::nonNull).toList();
    }

    /**
     * What an {@code /api/**} request is: not a test-session request (empty), the test account it acts as, or refused
     * ({@link AccountFailure} 401) without the Gateway secret, with a bearer token, or with a malformed account id. The
     * whole decision stays here, so the caller acts only on its result.
     */
    public java.util.Optional<AccountPrincipal> requestAccount(HttpServletRequest request) {
        String account = request.getHeader(ACCOUNT_HEADER);
        boolean gateway = fromGateway(request);
        if (account == null) return java.util.Optional.empty();
        if (!gateway) throw refused(request, "GATEWAY_SECRET_INVALID", null);
        if (request.getHeader("Authorization") != null) throw refused(request, "BEARER_TOKEN_WITH_TEST_ACCOUNT", null);
        UUID id;
        try { id = UUID.fromString(account); } catch (IllegalArgumentException malformed) { throw refused(request, "TEST_ACCOUNT_MALFORMED", null); }
        AccountPrincipal principal;
        try { principal = actAs(request.getHeader(HOLDER_HEADER), id); }
        catch (AccountFailure denied) { throw refused(request, denied.code(), id); }
        UUID holder = holder(request.getHeader(HOLDER_HEADER));
        if (holder != null) request.setAttribute(REQUESTER_ATTRIBUTE, holder);
        return java.util.Optional.of(principal);
    }

    /** One WARN line naming why, then the same 401 for every reason. */
    private static AccountFailure refused(HttpServletRequest request, String reason, UUID target) {
        Events.refused(Events.EVENT, "test-session.request", reason).addKeyValue("target.id", target).addKeyValue("url.path", Events.path(request))
                .log("Test-session request {} {} refused: {}", request.getMethod(), Events.path(request), reason);
        return new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
    }

    /** DLV-969 S35: the Gateway starts a test session; one line says who acts as which test account, or why not. */
    public AccountPrincipal start(String holderSubject, UUID accountId) {
        try {
            var principal = actAs(holderSubject, accountId);
            UUID holder = holder(holderSubject);
            Events.success(Events.EVENT, "test-session.start").addKeyValue("account.id", holder).addKeyValue("target.id", principal.accountId())
                    .log("Test session started: account {} acts as test account {}", holder, principal.accountId());
            return principal;
        } catch (AccountFailure refused) {
            Events.refused(Events.EVENT, "test-session.start", refused.code()).addKeyValue("account.id", holder(holderSubject)).addKeyValue("target.id", accountId)
                    .log("Test session refused for test account {}: {}", accountId, refused.code());
            throw refused;
        }
    }

    /** The gate holder's account id for the log line, or null when unknown; looking it up never fails the session. */
    private UUID holder(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 256) return null;
        try {
            // Any linked sign-in (S36) names the same account, as for the holder's role.
            return jdbc.queryForList(com.lookahead.domain.cloud.CloudIdentities.ACCOUNT_OF_IDENTITY, UUID.class, issuer, subject, issuer, subject)
                    .stream().findFirst().orElse(null);
        } catch (RuntimeException unknown) { return null; }
    }

    /** The test account a gate holder acts as for this request; refused unless every rule above holds. */
    public AccountPrincipal actAs(String holderSubject, UUID accountId) {
        policy.requireCloud();
        if (accountId == null) throw new AccountFailure(422, "TEST_ACCOUNT_REQUIRED", "Choose a test account.");
        String role = holderRole(holderSubject);
        var target = targets(accountId).stream().findFirst()
                .orElseThrow(() -> new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "This test account is not available."));
        if (!TestAccountTemplates.allows(role, roles(target.accountId())))
            throw new AccountFailure(403, "TEST_SESSION_FORBIDDEN", "Your role cannot use this test account.");
        return new AccountPrincipal(target.accountId(), target.username(), target.displayName(), true, false, null);
    }

    /**
     * {@link TestAccountTemplates#role}: "owner", "co-author", "admin" (a dual-role person gets the lower) or "learner"
     * for an admitted, enabled, not disabled or deleted account; "" for anyone else. Any linked sign-in (S36) counts.
     */
    private String holderRole(String subject) { return holderOf(subject).role(); }
    private Holder holderOf(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 256) return NOBODY;
        var holders = jdbc.query("""
                SELECT c.account_id,c.author_access FROM cloud_accounts c WHERE c.account_id IN (%s) AND c.admitted AND c.enabled
                AND NOT EXISTS (SELECT 1 FROM cloud_account_states s WHERE s.account_id=c.account_id)
                """.formatted(com.lookahead.domain.cloud.CloudIdentities.ACCOUNT_OF_IDENTITY),
                (row, n) -> new Object[]{row.getObject(1, UUID.class), row.getBoolean(2)}, issuer, subject, issuer, subject);
        if (holders.isEmpty()) return NOBODY;
        boolean owner = (Boolean) holders.getFirst()[1];
        UUID account = (UUID) holders.getFirst()[0];
        return new Holder(account, TestAccountTemplates.role(owner, owner ? Set.of() : roles(account)));
    }

    private Set<String> roles(UUID account) {
        return new HashSet<>(jdbc.queryForList("SELECT jsonb_array_elements_text(roles) FROM staff_access WHERE account_id=?", String.class, account));
    }

    private List<Target> targets(UUID only) {
        String sql = """
                SELECT r.account_id,r.username,c.display_name FROM test_account_registry r
                JOIN cloud_accounts c ON c.account_id=r.account_id AND c.issuer=? AND c.admitted AND c.enabled
                WHERE r.creation_state='active' AND (r.initialization='created' OR r.creator_id IS NOT NULL)
                """;
        var mapper = (org.springframework.jdbc.core.RowMapper<Target>) (row, n) ->
                new Target(row.getObject(1, UUID.class), row.getString(2), row.getString(3));
        return only == null ? jdbc.query(sql + " ORDER BY r.username", mapper, TestAccountRoster.TEST_ISSUER)
                : jdbc.query(sql + " AND r.account_id=?", mapper, TestAccountRoster.TEST_ISSUER, only);
    }
}
