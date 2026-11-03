package com.lookahead.domain.testaccounts;

import com.lookahead.domain.cloud.CognitoSettings;
import com.lookahead.learning.content.exception.AccountFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
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
    @Bean DevTestSessions devTestSessions(JdbcTemplate jdbc, TestAccountPolicy policy, CognitoSettings cognito) {
        return new DevTestSessions(jdbc, policy, cognito.issuer(), cognito.gatewaySecret());
    }

    @Bean @Order(0) SecurityFilterChain devTestSessionSecurity(HttpSecurity http, DevTestSessions sessions) throws Exception {
        // Service to service: no browser, cookie or session, so no CSRF token; the Gateway secret is the credential.
        return http.securityMatcher("/internal/v1/test-sessions/**")
                .csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        if (!sessions.fromGateway(request)) { Filter.refuse(response); return; }
                        chain.doFilter(request, response);
                    }
                }, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    /**
     * On {@code /api/**}: a request with {@link DevTestSessions#ACCOUNT_HEADER} acts as that test account, but only with the
     * Gateway secret and no bearer token; anything else is refused, never passed on unauthenticated.
     */
    public static final class Filter extends OncePerRequestFilter {
        private final DevTestSessions sessions;
        public Filter(DevTestSessions sessions) { this.sessions = sessions; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String account = request.getHeader(DevTestSessions.ACCOUNT_HEADER);
            if (account == null) { chain.doFilter(request, response); return; }
            boolean gateway = sessions.fromGateway(request);
            if (!gateway || request.getHeader("Authorization") != null) { refuse(response); return; }
            UsernamePasswordAuthenticationToken authentication;
            try {
                var principal = sessions.authenticate(request.getHeader(DevTestSessions.HOLDER_HEADER), UUID.fromString(account));
                authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("SCOPE_account"),
                        new SimpleGrantedAuthority("SCOPE_content"), new SimpleGrantedAuthority("SCOPE_support")));
            } catch (IllegalArgumentException | AccountFailure refused) { refuse(response); return; }
            catch (RuntimeException unavailable) {
                response.setStatus(503); response.setHeader("Cache-Control", "no-store"); response.setContentType("application/json");
                response.getWriter().write("{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}");
                return;
            }
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
