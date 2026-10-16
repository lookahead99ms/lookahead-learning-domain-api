package com.lookahead.domain.testaccounts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

/** Dedicated Infra credential: the read-only token verifier cannot maintain accounts. */
@Configuration
public class TestAccountSecurity {
    @Bean @Order(-1)
    SecurityFilterChain testAccountSecurityChain(HttpSecurity http, Environment environment, TestAccountPolicy policy) throws Exception {
        String secret = environment.getProperty("app.test-accounts.operator-secret", "");
        if (environment.getProperty("app.test-accounts.enabled", Boolean.class, false)) {
            policy.requireEnabled();
            if (secret.length() < 32 || secret.equals(environment.getProperty("app.identity.verifier-secret"))
                    || secret.equals(environment.getProperty("app.oauth.client-secret")))
                throw new IllegalStateException("Test account maintenance requires a separate operator secret.");
        }
        byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
        var provider = new AuthenticationProvider() {
            @Override public Authentication authenticate(Authentication request) {
                try { policy.requireEnabled(); }
                catch (com.lookahead.learning.content.exception.AccountFailure disabled) {
                    throw new BadCredentialsException("Test account maintenance is disabled");
                }
                boolean matches = MessageDigest.isEqual(expected,
                        String.valueOf(request.getCredentials()).getBytes(StandardCharsets.UTF_8));
                if (expected.length < 32 || !"lookahead-test-account-operator".equals(request.getName()) || !matches)
                    throw new BadCredentialsException("Invalid operator credentials");
                return UsernamePasswordAuthenticationToken.authenticated(request.getName(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEST_ACCOUNT_OPERATOR")));
            }
            @Override public boolean supports(Class<?> type) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
            }
        };
        return http.securityMatcher("/internal/v1/test-accounts/**")
                .addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
                    @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                            throws jakarta.servlet.ServletException, java.io.IOException {
                        // Basic credentials can be ambient in browsers. Permit only non-browser JSON
                        // maintenance calls; no CORS/preflight or simple form request may reach seeding.
                        String contentType = request.getContentType();
                        boolean json = contentType != null && contentType.split(";", 2)[0].strip()
                                .equalsIgnoreCase("application/json");
                        if (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null
                                || ("POST".equals(request.getMethod()) && !json)) {
                            response.setStatus(403);
                            response.setHeader("Cache-Control", "no-store");
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                .authenticationManager(new ProviderManager(provider))
                .csrf(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST,
                        "/internal/v1/test-accounts/seed")
                        .hasRole("TEST_ACCOUNT_OPERATOR").anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, failure) -> {
                    response.setStatus(401); response.setHeader("Cache-Control", "no-store");
                })).build();
    }
}
