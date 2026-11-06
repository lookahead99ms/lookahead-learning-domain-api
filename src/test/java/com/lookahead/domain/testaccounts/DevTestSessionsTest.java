package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.Mockito.*;

/** DLV-969 S35: the test-session filter on /api/**, the Gateway's internal routes and the roster route, without a database. */
class DevTestSessionsTest {
    static final UUID ACCOUNT = UUID.fromString("2c7e9a4d-1b3f-4c5e-8d9a-0b1c2d3e4f5a");
    static final AccountPrincipal LEARNER = new AccountPrincipal(ACCOUNT, "learner07@lookahead.dev", "Learner07", true, false, null);

    /** Answers like the real service: the Gateway secret is "secret", holder "sub-admin" may use ACCOUNT only. */
    static final class Stub extends DevTestSessions {
        RuntimeException failure;
        Stub() { super(null, null, "issuer", "secret"); }
        @Override public AccountPrincipal actAs(String holder, UUID account) {
            if (failure != null) throw failure;
            if (account == null) throw new AccountFailure(422, "TEST_ACCOUNT_REQUIRED", "Choose a test account.");
            if (!"sub-admin".equals(holder)) throw new AccountFailure(403, "TEST_SESSION_FORBIDDEN", "No");
            if (!ACCOUNT.equals(account)) throw new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "No");
            return LEARNER;
        }
        @Override public List<Usable> usable(String holder) {
            return "sub-admin".equals(holder) ? List.of(new Usable(ACCOUNT, LEARNER.username(), LEARNER.displayName(), List.of(), "Free · no study plan")) : List.of();
        }
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    static MockHttpServletRequest request(String secret, String holder, String account) {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        if (secret != null) request.addHeader(DevTestSessions.SECRET_HEADER, secret);
        if (holder != null) request.addHeader(DevTestSessions.HOLDER_HEADER, holder);
        if (account != null) request.addHeader(DevTestSessions.ACCOUNT_HEADER, account);
        return request;
    }

    /** Runs the filter; returns the authentication the rest of the chain saw, or null when the chain never ran. */
    static Authentication run(DevTestSessions sessions, MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        var seen = new AtomicReference<Authentication>();
        var called = new boolean[1];
        FilterChain chain = (req, res) -> { called[0] = true; seen.set(SecurityContextHolder.getContext().getAuthentication()); };
        new DevTestSessionConfiguration.Filter(sessions).doFilter(request, response, chain);
        return called[0] ? (seen.get() == null ? new org.springframework.security.authentication.AnonymousAuthenticationToken("k", "anonymous",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))) : seen.get()) : null;
    }

    /** DLV-969 (owner, 2026-11-04): a test session that cannot be checked answers 503 and says why, once a minute. */
    @Test void aFailingTestSessionCheckIsLoggedWithItsTypes() throws Exception {
        var sessions = mock(DevTestSessions.class);
        when(sessions.requestAccount(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db.internal",
                new java.sql.SQLException("down", "08001")));
        try (var log = com.lookahead.domain.LogCapture.start()) {
            for (int i = 0; i < 2; i++) {
                var response = new MockHttpServletResponse();
                assertThat(run(sessions, request("secret", "holder", UUID.randomUUID().toString()), response)).isNull();
                assertThat(response.getStatus()).isEqualTo(503);
            }
            var line = log.only("test-session.request");
            assertThat(line.getFormattedMessage()).isEqualTo("Test session check failed (DataAccessResourceFailureException < SQLException, SQL state 08001);"
                    + " answered 503 IDENTITY_UNAVAILABLE");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("reason", "TEST_SESSION_UNAVAILABLE").containsEntry("db.sqlState", "08001");
            log.containsNone("db.internal");
        }
    }

    @Test void aRequestWithoutTheTestAccountHeaderPassesUntouched() throws Exception {
        var response = new MockHttpServletResponse();
        var seen = run(new Stub(), request(null, null, null), response);
        assertThat(seen).isInstanceOf(org.springframework.security.authentication.AnonymousAuthenticationToken.class);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void theGatewayActsAsTheTestAccountWithTheAccountScopes() throws Exception {
        var response = new MockHttpServletResponse();
        var seen = run(new Stub(), request("secret", "sub-admin", ACCOUNT.toString()), response);
        assertThat(seen.getPrincipal()).isEqualTo(LEARNER);
        assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactlyInAnyOrder("SCOPE_account", "SCOPE_content", "SCOPE_support");
    }

    @Test void everythingElseIsRefusedAndNeverPassedOnUnauthenticated() throws Exception {
        var withBearer = request("secret", "sub-admin", ACCOUNT.toString());
        withBearer.addHeader("Authorization", "Bearer x");
        for (var refused : List.of(request(null, "sub-admin", ACCOUNT.toString()), request("wrong", "sub-admin", ACCOUNT.toString()),
                request("secret", "sub-learner", ACCOUNT.toString()), request("secret", "sub-admin", UUID.randomUUID().toString()),
                request("secret", "sub-admin", "not-a-uuid"), request("secret", null, ACCOUNT.toString()), withBearer)) {
            var response = new MockHttpServletResponse();
            assertThat(run(new Stub(), refused, response)).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        var unavailable = new Stub(); unavailable.failure = new IllegalStateException("database down");
        var response = new MockHttpServletResponse();
        assertThat(run(unavailable, request("secret", "sub-admin", ACCOUNT.toString()), response)).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("IDENTITY_UNAVAILABLE");
    }

    @Test void theInternalRoutesAnswerForTheGateHolder() {
        var routes = new DevTestSessionsController(new Stub());
        var response = new MockHttpServletResponse();
        assertThat(routes.accounts("sub-admin", response).data()).asInstanceOf(LIST).hasSize(1);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(routes.accounts("sub-learner", new MockHttpServletResponse()).data()).asInstanceOf(LIST).isEmpty();
        assertThat(routes.start("sub-admin", new DevTestSessionsController.Start(ACCOUNT), new MockHttpServletResponse()).data())
                .isEqualTo(Map.of("accountId", ACCOUNT, "username", LEARNER.username(), "displayName", LEARNER.displayName()));
        assertThatThrownBy(() -> routes.start("sub-admin", null, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.status()).isEqualTo(422));
        assertThatThrownBy(() -> routes.start("sub-admin", new DevTestSessionsController.Start(null), new MockHttpServletResponse()))
                .isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> routes.start("sub-learner", new DevTestSessionsController.Start(ACCOUNT), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.status()).isEqualTo(403));
    }

    @Test void theCreateRouteSendsARosterRequestToTheRosterAndAddsItsProgressToTheAllowance() {
        // Subclass mock maker: the image build runs without the inline agent (as in AuthorPreviewActivationTest).
        var settings = withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS).strictness(Strictness.LENIENT);
        var created = mock(CreatedTestAccounts.class, settings);
        var roster = mock(DevTestRoster.class, settings);
        var accounts = mock(TestAccountsService.class, settings);
        var controller = new TestAccountsController(accounts, created, roster);
        var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
        var status = new DevTestRoster.Status(27, 5, 0);
        when(roster.next(owner)).thenReturn(status);
        assertThat(controller.create(owner, new CreatedTestAccounts.Create(null, null, null, true)).data()).isEqualTo(status);
        verify(created, never()).create(any(), any());
        var single = new CreatedTestAccounts.Create(UUID.randomUUID(), null, "learner02", false);
        when(created.create(owner, single)).thenReturn(new CreatedTestAccounts.Created(ACCOUNT, "test-1@lookahead.dev", true));
        assertThat(controller.create(owner, single).data()).isEqualTo(new CreatedTestAccounts.Created(ACCOUNT, "test-1@lookahead.dev", true));
        verify(roster, times(1)).next(owner);
        when(created.allowance(owner)).thenReturn(new CreatedTestAccounts.Allowance(1, 0, 1));
        when(roster.statusFor(owner)).thenReturn(status);
        assertThat(controller.allowance(owner).data()).isEqualTo(new CreatedTestAccounts.Allowance(1, 0, 1, status, null));
        assertThat(status.remaining()).isEqualTo(22);
        assertThat(new CreatedTestAccounts.Create(ACCOUNT, "x").toString()).isEqualTo("Create[redacted]");
        // DLV-969 (owner, 2026-11-04): {"restoreRoster": true, "confirm": "RESTORE"} on the same route goes to the roster's Restore.
        when(roster.restore(owner, new DevTestRoster.Restore("RESTORE"))).thenReturn(new DevTestRoster.Restored(2));
        assertThat(controller.create(owner, new CreatedTestAccounts.Create(null, null, null, false, true, "RESTORE")).data()).isEqualTo(new DevTestRoster.Restored(2));
        assertThat(controller.create(owner, new CreatedTestAccounts.Create(null, null, null, true, true, "RESTORE")).data()).isEqualTo(new DevTestRoster.Restored(2));
        verify(roster, times(1)).next(owner);
        verify(created, times(1)).create(any(), any());
        // DLV-969 (owner, 2026-11-04): {"purchaseDecision": {...}} on the same route is a decision on a DEV purchase request.
        var decision = new com.lookahead.domain.membership.MembershipService.Decision(UUID.randomUUID(), "approve");
        var decided = new tools.jackson.databind.ObjectMapper().createObjectNode().put("decision", "approve");
        when(accounts.decidePurchase(owner, decision)).thenReturn(decided);
        assertThat(controller.create(owner, new CreatedTestAccounts.Create(null, null, null, true, true, "RESTORE", decision)).data()).isEqualTo(decided);
        verify(roster, times(1)).next(owner);
        verify(roster, times(2)).restore(any(), any());
        verify(created, times(1)).create(any(), any());
    }
}
