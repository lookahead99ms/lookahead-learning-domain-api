package com.lookahead.domain.security.config;

import com.lookahead.domain.security.filter.TestSessionFilter;
import com.lookahead.domain.security.handler.ApiSecurityHandler;
import com.lookahead.domain.security.handler.ProtectedContentAuthorizationHandler;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class ApiSecurityConfiguration {
  static final String DEV = "'${app.deployment-environment:}' == 'dev'";
  static final String NOT_DEV = "'${app.deployment-environment:}' != 'dev'";

  /**
   * Every environment but DEV: the API chain without test sessions. Both methods are the bean
   * {@code apiSecurity}.
   */
  @Bean("apiSecurity")
  @Order(2)
  @ConditionalOnExpression(NOT_DEV)
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      JwtDecoder decoder,
      Converter<Jwt, AbstractAuthenticationToken> authenticationConverter)
      throws Exception {
    return chain(http, decoder, authenticationConverter, null);
  }

  /**
   * DEV: the same chain with the test-session filter. DLV-1016: the condition is the one that
   * defines the test-session beans ({@link com.lookahead.domain.config.TestSessionConfiguration});
   * before, one bean method asked whether a test-session service existed.
   */
  @Bean("apiSecurity")
  @Order(2)
  @ConditionalOnExpression(DEV)
  SecurityFilterChain apiSecurityWithTestSessions(
      HttpSecurity http,
      JwtDecoder decoder,
      Converter<Jwt, AbstractAuthenticationToken> authenticationConverter,
      TestSessionService testSessions)
      throws Exception {
    return chain(http, decoder, authenticationConverter, testSessions);
  }

  private static SecurityFilterChain chain(
      HttpSecurity http,
      JwtDecoder decoder,
      Converter<Jwt, AbstractAuthenticationToken> authenticationConverter,
      TestSessionService sessions)
      throws Exception {
    // DLV-969 S35 (DEV only): a Gateway test-session request carries no bearer token, so it needs
    // no CSRF token
    // either (as bearer requests); the filter refuses it unless the Gateway secret is valid.
    if (sessions != null)
      http.addFilterBefore(new TestSessionFilter(sessions), BearerTokenAuthenticationFilter.class);
    return http.cors(Customizer.withDefaults())
        .csrf(csrf -> {
          csrf.csrfTokenRepository(new CookieCsrfTokenRepository());
          if (sessions != null) csrf.ignoringRequestMatchers(TestSessionFilter::requested);
        })
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth ->
            auth.requestMatchers(HttpMethod.GET, "/api/v1/membership/plans", "/api/v1/sales/active")
                .permitAll()
                .requestMatchers(
                    "/api/v1/account/access-summary",
                    "/api/v1/account/access-records",
                    "/api/v1/account/purchases",
                    "/api/v1/account/purchases/**",
                    "/api/v1/admin/accounts/**",
                    "/api/v1/admin/sales",
                    "/api/v1/admin/sales/**",
                    "/api/v1/admin/membership/**")
                .hasAuthority("SCOPE_account")
                // DLV-914: /actuator/info reports build.version, releaseTag and sourceRevision;
                // read-only, nothing else exposed.
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/status",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/content/**")
                .access(new ProtectedContentAuthorizationHandler())
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/author/review-artifacts",
                    "/api/v1/author/review-artifacts/*/events")
                .hasAuthority("SCOPE_account")
                .requestMatchers(HttpMethod.POST, "/api/v1/author/review-artifacts/*/events")
                .hasAuthority("SCOPE_account")
                .requestMatchers("/api/v1/support")
                .hasAuthority("SCOPE_support")
                .requestMatchers(
                    "/api/v1/admin/test-accounts",
                    "/api/v1/admin/test-accounts/**",
                    "/api/v1/admin/access",
                    "/api/v1/admin/access/**",
                    "/api/v1/account/permissions",
                    "/api/v1/sweeps/run")
                .hasAuthority("SCOPE_account")
                // DLV-969 S36: the person's own merge of accounts sharing their verified email
                // (cloud mode only).
                .requestMatchers("/api/v1/account/merge/**")
                .hasAuthority("SCOPE_account")
                .requestMatchers("/api/v1/platform-reviews", "/api/v1/platform-reviews/**")
                .hasAuthority("SCOPE_account")
                // DLV-971: the delivery plan the Domain serves and edits; the same session and
                // bearer as platform reviews.
                .requestMatchers("/api/v1/delivery/plan", "/api/v1/delivery/plan/**")
                .hasAuthority("SCOPE_account")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/invitations/activate")
                .hasAuthority("SCOPE_account")
                .requestMatchers(
                    "/api/v1/auth/me",
                    "/api/v1/account-catalog",
                    "/api/v1/author/previews/access",
                    "/api/v1/plans",
                    "/api/v1/plans/**")
                .hasAuthority("SCOPE_account")
                .anyRequest()
                .denyAll())
        .oauth2ResourceServer(resource ->
            resource
                .jwt(
                    jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authenticationConverter))
                .authenticationEntryPoint(new ApiSecurityHandler()))
        .build();
  }
}
