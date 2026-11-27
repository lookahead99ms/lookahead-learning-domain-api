package com.lookahead.domain.security.config;

import com.lookahead.domain.security.filter.TestAccountOperatorFilter;
import com.lookahead.domain.security.handler.TestAccountSecurityHandler;
import com.lookahead.domain.security.token.TestAccountOperatorVerifier;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import com.lookahead.domain.validator.TestAccountOperatorSecretValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

/** Dedicated Infra credential: the read-only token verifier cannot maintain accounts. */
@Configuration
public class TestAccountSecurityConfiguration {
    @Bean @Order(-1)
    SecurityFilterChain testAccountSecurityChain(HttpSecurity http, Environment environment, TestAccountModeService policy) throws Exception {
        var provider = new TestAccountOperatorVerifier(policy, TestAccountOperatorSecretValidator.validated(environment));
        return http.securityMatcher("/internal/v1/test-accounts/**")
                .addFilterBefore(new TestAccountOperatorFilter(), CsrfFilter.class)
                .authenticationManager(new ProviderManager(provider))
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository())).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/internal/v1/test-accounts/csrf").hasRole("TEST_ACCOUNT_OPERATOR")
                        .requestMatchers(HttpMethod.POST,
                        "/internal/v1/test-accounts/seed")
                        .hasRole("TEST_ACCOUNT_OPERATOR").anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(new TestAccountSecurityHandler())).build();
    }
}
