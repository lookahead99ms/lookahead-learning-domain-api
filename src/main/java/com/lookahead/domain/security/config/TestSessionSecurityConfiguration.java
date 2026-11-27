package com.lookahead.domain.security.config;

import com.lookahead.domain.security.filter.TestSessionBffFilter;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * DLV-969 S35: test sessions exist only in DEV ({@code app.deployment-environment=dev}); PROD never wires them. The
 * internal routes take only the Gateway's calls; {@link com.lookahead.domain.security.filter.TestSessionFilter} lets {@code /api/**} act as the test account.
 * The test-session services are beans of {@code config.TestSessionConfiguration}, under the same condition.
 */
@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev'")
public class TestSessionSecurityConfiguration {
    @Bean @Order(0) SecurityFilterChain testSessionSecurity(HttpSecurity http, TestSessionService sessions) throws Exception {
        // Service to service, with no browser cookie or session. CSRF stays on; only a request carrying the valid Gateway
        // secret skips it, and the gate filter below, which runs before CSRF, refuses every other request.
        var bffOnly = new TestSessionBffFilter(sessions);
        return http.securityMatcher("/internal/v1/test-sessions/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers(bffOnly::fromBff)).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(bffOnly, CsrfFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
