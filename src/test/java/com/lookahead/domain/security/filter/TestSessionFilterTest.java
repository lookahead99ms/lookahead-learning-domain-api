package com.lookahead.domain.security.filter;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.testaccounts.TestAccountIdentities;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLV-969 S35: the test-session filter on {@code /api/**}, without a database. DLV-1016: the filter reads the request's
 * headers and writes the refusal line; these tests pin the order in which the headers are read, how far, and each line.
 */
class TestSessionFilterTest {
    static final String SECRET = SignInBffSecret.HEADER, HOLDER = TestSessionFilter.HOLDER_HEADER, TEST_ACCOUNT = TestSessionFilter.ACCOUNT_HEADER;
    static final UUID ACCOUNT = UUID.fromString("2c7e9a4d-1b3f-4c5e-8d9a-0b1c2d3e4f5a");
    static final UUID ADMIN = UUID.fromString("7d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b");
    static final AccountPrincipal LEARNER = new AccountPrincipal(ACCOUNT, "learner07@lookahead.dev", "Learner07", true, false, null);
    static final String BODY_401 = "{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to continue\"}";

    /** Who the store says a gate holder is; null for an unknown holder. */
    Function<String, UUID> holderAccount = subject -> null;
    /**
     * A hand-written store, not a Mockito mock (other test classes switch Mockito's mock maker inside the same JVM, and
     * stubbing a mock after that fails with Mockito's own assertion): it answers who a gate holder is and nothing else.
     */
    final TestAccountIdentities store = (TestAccountIdentities) Proxy.newProxyInstance(TestAccountIdentities.class.getClassLoader(),
            new Class<?>[] {TestAccountIdentities.class}, (proxy, method, arguments) -> {
                if (!method.getName().equals("accountOf")) throw new UnsupportedOperationException(method.getName());
                assertThat(arguments[0]).isEqualTo("issuer");
                return holderAccount.apply((String) arguments[1]);
            });

    /** The real service with the Gateway BFF secret "secret" and issuer "issuer"; only "who may act as whom" is answered here: holder "sub-admin" may use ACCOUNT. */
    final class Sessions extends TestSessionService {
        RuntimeException failure;
        final List<String> asked = new ArrayList<>();
        Sessions() { super(store, null, "issuer", "secret"); }
        @Override public AccountPrincipal actAs(String holder, UUID account) {
            asked.add(holder + " as " + account);
            if (failure != null) throw failure;
            if (!"sub-admin".equals(holder)) throw new AccountFailure(403, "TEST_SESSION_FORBIDDEN", "No");
            if (!ACCOUNT.equals(account)) throw new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "No");
            return LEARNER;
        }
    }

    /** Notes every header the filter reads, in order. */
    static final class Reads extends HttpServletRequestWrapper {
        final List<String> headers = new ArrayList<>();
        Reads(MockHttpServletRequest request) { super(request); }
        @Override public String getHeader(String name) { headers.add(name); return super.getHeader(name); }
        List<String> headers() { return headers; }
    }

    /** What one run of the filter did. {@code seen}: the authentication the rest of the chain saw; {@code passed}: whether the chain ran at all. */
    record Run(Reads request, MockHttpServletResponse response, boolean passed, Authentication seen) {}

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    static MockHttpServletRequest request(String secret, String holder, String account) {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        if (secret != null) request.addHeader(SECRET, secret);
        if (holder != null) request.addHeader(HOLDER, holder);
        if (account != null) request.addHeader(TEST_ACCOUNT, account);
        return request;
    }

    static Run run(TestSessionService sessions, MockHttpServletRequest request) throws Exception {
        SecurityContextHolder.clearContext();
        var seen = new AtomicReference<Authentication>();
        var called = new boolean[1];
        FilterChain chain = (req, res) -> { called[0] = true; seen.set(SecurityContextHolder.getContext().getAuthentication()); };
        var reads = new Reads(request);
        var response = new MockHttpServletResponse();
        new TestSessionFilter(sessions).doFilter(reads, response, chain);
        return new Run(reads, response, called[0], seen.get());
    }

    @Test void aRequestWithoutTheTestAccountHeaderPassesUntouched() throws Exception {
        var ran = run(new Sessions(), request(null, null, null));
        assertThat(ran.passed()).isTrue();
        assertThat(ran.seen()).as("nobody was signed in by the filter").isNull();
        assertThat(ran.response().getStatus()).isEqualTo(200);
    }

    @Test void theGatewayActsAsTheTestAccountWithTheAccountScopes() throws Exception {
        var ran = run(new Sessions(), request("secret", "sub-admin", ACCOUNT.toString()));
        assertThat(ran.passed()).isTrue();
        assertThat(ran.seen().getPrincipal()).isEqualTo(LEARNER);
        assertThat(ran.seen().isAuthenticated()).isTrue();
        assertThat(ran.seen().getAuthorities()).extracting(Object::toString).containsExactlyInAnyOrder("SCOPE_account", "SCOPE_content", "SCOPE_support");
    }

    @Test void everythingElseIsRefusedAndNeverPassedOnUnauthenticated() throws Exception {
        var withBearer = request("secret", "sub-admin", ACCOUNT.toString());
        withBearer.addHeader("Authorization", "Bearer x");
        for (var refused : List.of(request(null, "sub-admin", ACCOUNT.toString()), request("wrong", "sub-admin", ACCOUNT.toString()),
                request("secret", "sub-learner", ACCOUNT.toString()), request("secret", "sub-admin", UUID.randomUUID().toString()),
                request("secret", "sub-admin", "not-a-uuid"), request("secret", null, ACCOUNT.toString()), withBearer)) {
            var ran = run(new Sessions(), refused);
            assertThat(ran.passed()).isFalse();
            assertThat(ran.response().getStatus()).isEqualTo(401);
            assertThat(ran.response().getContentAsString()).isEqualTo(BODY_401);
            assertThat(ran.response().getContentType()).isEqualTo("application/json");
            assertThat(ran.response().getHeader("Cache-Control")).isEqualTo("no-store");
        }
        var unavailable = new Sessions(); unavailable.failure = new IllegalStateException("database down");
        var ran = run(unavailable, request("secret", "sub-admin", ACCOUNT.toString()));
        assertThat(ran.passed()).isFalse();
        assertThat(ran.response().getStatus()).isEqualTo(503);
        assertThat(ran.response().getContentAsString()).contains("IDENTITY_UNAVAILABLE");
        assertThat(ran.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /** DLV-969 (owner, 2026-11-04): a test session that cannot be checked answers 503 and says why, once a minute. */
    @Test void aFailingTestSessionCheckIsLoggedWithItsTypes() throws Exception {
        var sessions = new Sessions();
        sessions.failure = SdkClientException.create("db.internal", new ConnectException("down"));
        try (var log = LogCapture.start()) {
            for (int i = 0; i < 2; i++) {
                var ran = run(sessions, request("secret", "holder", UUID.randomUUID().toString()));
                assertThat(ran.passed()).isFalse();
                assertThat(ran.response().getStatus()).isEqualTo(503);
            }
            var line = log.only("test-session.request");
            assertThat(line.getFormattedMessage()).isEqualTo("Test session check failed (SdkClientException < ConnectException); answered 503 IDENTITY_UNAVAILABLE");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "TEST_SESSION_UNAVAILABLE").containsEntry("error.type", "SdkClientException < ConnectException");
            log.containsNone("db.internal");
        }
    }

    /**
     * The order is fixed: the test-account header, then the Gateway BFF's secret (compared on every request, also one that
     * asks for no test account), then the bearer header, then the gate holder (once to decide, once more for the record).
     * A request is read only as far as its decision needs.
     */
    @Test void theHeadersAreReadInOneFixedOrderAndOnlyAsFarAsTheDecisionNeeds() throws Exception {
        holderAccount = subject -> "sub-admin".equals(subject) ? ADMIN : null;
        var sessions = new Sessions();
        // No test account asked for: the secret header is still read, whatever it holds, and the request goes on.
        for (var plain : List.of(request(null, null, null), request("wrong", "sub-admin", null), request("secret", "sub-admin", null))) {
            var ran = run(sessions, plain);
            assertThat(ran.request().headers()).containsExactly(TEST_ACCOUNT, SECRET);
            assertThat(ran.passed()).isTrue(); assertThat(ran.seen()).isNull();
        }
        // Refused for the secret: nothing after it is read, although a bearer token and a malformed account are there too.
        var everythingWrong = request("wrong", "sub-learner", "not-a-uuid");
        everythingWrong.addHeader("Authorization", "Bearer x");
        assertThat(run(sessions, everythingWrong).request().headers()).containsExactly(TEST_ACCOUNT, SECRET);
        assertThat(run(sessions, request(null, "sub-admin", ACCOUNT.toString())).request().headers()).containsExactly(TEST_ACCOUNT, SECRET);
        // Refused for the bearer token, then for the malformed account: the gate holder is not read.
        var withBearer = request("secret", "sub-learner", "not-a-uuid");
        withBearer.addHeader("Authorization", "Bearer x");
        assertThat(run(sessions, withBearer).request().headers()).containsExactly(TEST_ACCOUNT, SECRET, "Authorization");
        assertThat(run(sessions, request("secret", "sub-learner", "not-a-uuid")).request().headers()).containsExactly(TEST_ACCOUNT, SECRET, "Authorization");
        assertThat(sessions.asked).as("nobody was asked to act before the secret, the bearer check and the account id were through").isEmpty();
        // Refused by the rules: the gate holder is read once.
        assertThat(run(sessions, request("secret", "sub-learner", ACCOUNT.toString())).request().headers()).containsExactly(TEST_ACCOUNT, SECRET, "Authorization", HOLDER);
        // Accepted: the gate holder is read a second time, for the record of who is behind the session.
        var accepted = run(sessions, request("secret", "sub-admin", ACCOUNT.toString()));
        assertThat(accepted.request().headers()).containsExactly(TEST_ACCOUNT, SECRET, "Authorization", HOLDER, HOLDER);
        assertThat(accepted.passed()).isTrue();
        assertThat(sessions.asked).containsExactly("sub-learner as " + ACCOUNT, "sub-admin as " + ACCOUNT);
    }

    @Test void eachRefusalWritesOneLineNamingTheReasonAndAnswersTheSame401() throws Exception {
        var other = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
        var withBearer = request("secret", "sub-admin", ACCOUNT.toString());
        withBearer.addHeader("Authorization", "Bearer x");
        var cases = new LinkedHashMap<MockHttpServletRequest, Object[]>();
        cases.put(request(null, "sub-admin", ACCOUNT.toString()), new Object[] {"GATEWAY_SECRET_INVALID", null});
        cases.put(request("secretx", "sub-admin", ACCOUNT.toString()), new Object[] {"GATEWAY_SECRET_INVALID", null});
        cases.put(withBearer, new Object[] {"BEARER_TOKEN_WITH_TEST_ACCOUNT", null});
        cases.put(request("secret", "sub-admin", "not-a-uuid"), new Object[] {"TEST_ACCOUNT_MALFORMED", null});
        cases.put(request("secret", "sub-learner", ACCOUNT.toString()), new Object[] {"TEST_SESSION_FORBIDDEN", ACCOUNT});
        cases.put(request("secret", null, ACCOUNT.toString()), new Object[] {"TEST_SESSION_FORBIDDEN", ACCOUNT});
        cases.put(request("secret", "sub-admin", other.toString()), new Object[] {"TEST_ACCOUNT_NOT_FOUND", other});
        for (var refusal : cases.entrySet()) {
            String reason = (String) refusal.getValue()[0];
            try (var log = LogCapture.start()) {
                var ran = run(new Sessions(), refusal.getKey());
                assertThat(ran.passed()).as(reason).isFalse();
                assertThat(ran.response().getStatus()).isEqualTo(401);
                assertThat(ran.response().getContentAsString()).isEqualTo(BODY_401);
                assertThat(refusal.getKey().getAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE)).isNull();
                assertThat(log.lines()).as("one line, nothing else").hasSize(1);
                var line = log.only("test-session.request");
                assertThat(line.getLevel()).isEqualTo(Level.WARN);
                assertThat(line.getLoggerName()).isEqualTo("lookahead.event");
                assertThat(line.getFormattedMessage()).isEqualTo("Test-session request GET /api/v1/auth/me refused: " + reason);
                var keys = LogCapture.keys(line);
                assertThat(new ArrayList<>(keys.keySet())).containsExactly("event.action", "event.outcome", "reason", "target.id", "url.path");
                assertThat(keys).containsEntry("event.outcome", "refused").containsEntry("reason", reason).containsEntry("target.id", refusal.getValue()[1])
                        .containsEntry("url.path", "/api/v1/auth/me");
            }
        }
    }

    /** DLV-969 (owner, 2026-11-04): an accepted request carries the gate holder's account id, so a DEV purchase request records who sent it. */
    @Test void anAcceptedRequestRecordsTheGateHolderWhenTheHolderIsKnown() throws Exception {
        try (var log = LogCapture.start()) {
            holderAccount = subject -> "sub-admin".equals(subject) ? ADMIN : null;
            var known = request("secret", "sub-admin", ACCOUNT.toString());
            assertThat(run(new Sessions(), known).passed()).isTrue();
            assertThat(known.getAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE)).isEqualTo(ADMIN);
            // Unknown to the store, or the store cannot say: the session still goes on, without the record.
            holderAccount = subject -> null;
            var unknown = request("secret", "sub-admin", ACCOUNT.toString());
            assertThat(run(new Sessions(), unknown).passed()).isTrue();
            assertThat(unknown.getAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE)).isNull();
            holderAccount = subject -> { throw new IllegalStateException("store down"); };
            var failing = request("secret", "sub-admin", ACCOUNT.toString());
            var ran = run(new Sessions(), failing);
            assertThat(ran.passed()).isTrue();
            assertThat(ran.seen().getPrincipal()).isEqualTo(LEARNER);
            assertThat(failing.getAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE)).isNull();
            // A request that asks for no test account never gets the record.
            var plain = request("secret", "sub-admin", null);
            assertThat(run(new Sessions(), plain).passed()).isTrue();
            assertThat(plain.getAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE)).isNull();
            assertThat(log.lines()).as("an accepted request writes no line").isEmpty();
        }
    }
}
