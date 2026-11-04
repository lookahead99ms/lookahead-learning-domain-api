package com.lookahead.domain.testaccounts;

import com.lookahead.domain.cloud.CognitoSettings;
import com.lookahead.learning.content.exception.AccountFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DLV-969 S35: test sessions exist only in DEV ({@code app.deployment-environment=dev}); PROD never wires them. The
 * internal routes take only the Gateway's calls; {@link Filter} lets {@code /api/**} act as the test account.
 */
@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev'")
public class DevTestSessionConfiguration {
    @Bean DevTestSessions devTestSessions(JdbcTemplate jdbc, TestAccountPolicy policy, CognitoSettings cognito,
            org.springframework.beans.factory.ObjectProvider<com.lookahead.domain.membership.MembershipService> memberships,
            org.springframework.beans.factory.ObjectProvider<com.lookahead.domain.access.StaffAliases> aliases) {
        var sessions = new DevTestSessions(jdbc, policy, cognito.issuer(), cognito.gatewaySecret());
        // DLV-969 (owner, 2026-11-04): the picker shows who upgraded each account (none without DEV purchase requests).
        sessions.configureMarkers(new UpgradeMarkers(jdbc, memberships.getIfAvailable(), aliases.getIfAvailable()));
        return sessions;
    }

    @Bean @Order(0) SecurityFilterChain devTestSessionSecurity(HttpSecurity http, DevTestSessions sessions) throws Exception {
        // Service to service, with no browser cookie or session. CSRF stays on; only a request carrying the valid Gateway
        // secret skips it, and the gate filter below, which runs before CSRF, refuses every other request.
        return http.securityMatcher("/internal/v1/test-sessions/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers(sessions::fromGateway)).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        if (!sessions.fromGateway(request)) {
                            com.lookahead.domain.Events.refused(com.lookahead.domain.Events.EVENT, "test-session.gateway-check", "GATEWAY_SECRET_INVALID")
                                    .addKeyValue("url.path", com.lookahead.domain.Events.path(request))
                                    .log("Test-session route {} refused: the call did not carry the Gateway secret", com.lookahead.domain.Events.path(request));
                            Filter.refuse(response); return;
                        }
                        chain.doFilter(request, response);
                    }
                }, org.springframework.security.web.csrf.CsrfFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    /**
     * On {@code /api/**}: a request with {@link DevTestSessions#ACCOUNT_HEADER} acts as that test account, but only with the
     * Gateway secret and no bearer token; anything else is refused, never passed on unauthenticated.
     */
    public static final class Filter extends OncePerRequestFilter {
        private static final com.lookahead.domain.Throttle UNAVAILABLE = new com.lookahead.domain.Throttle();
        private final DevTestSessions sessions;
        public Filter(DevTestSessions sessions) { this.sessions = sessions; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            java.util.Optional<com.lookahead.learning.content.security.AccountPrincipal> principal;
            try { principal = sessions.requestAccount(request); }
            catch (AccountFailure refused) { refuse(response); return; }
            catch (RuntimeException unavailable) {
                // DLV-969 (owner, 2026-11-04): the 503 names its cause once a minute at most.
                String types = com.lookahead.domain.Events.types(unavailable), state = com.lookahead.domain.Events.sqlState(unavailable);
                if (UNAVAILABLE.due(types)) com.lookahead.domain.Events.failed(com.lookahead.domain.Events.EVENT, "test-session.request", "TEST_SESSION_UNAVAILABLE")
                        .addKeyValue("error.type", types).addKeyValue("db.sqlState", state)
                        .log("Test session check failed ({}{}); answered 503 IDENTITY_UNAVAILABLE", types, state == null ? "" : ", SQL state " + state);
                response.setStatus(503); response.setHeader("Cache-Control", "no-store"); response.setContentType("application/json");
                response.getWriter().write("{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}");
                return;
            }
            if (principal.isEmpty()) { chain.doFilter(request, response); return; }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(principal.get(), null, List.of(new SimpleGrantedAuthority("SCOPE_account"),
                    new SimpleGrantedAuthority("SCOPE_content"), new SimpleGrantedAuthority("SCOPE_support")));
            var context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.getContextHolderStrategy().setContext(context);
            chain.doFilter(request, response);
        }
        static void refuse(HttpServletResponse response) throws IOException {
            response.setStatus(401); response.setHeader("Cache-Control", "no-store"); response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to continue\"}");
        }
    }
}
