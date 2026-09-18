package com.lookahead.domain.security;

import com.lookahead.learning.content.repository.AccountRepository;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class DomainSecurityConfiguration {
    @Bean IdentitySettings identitySettings(Environment environment) { return IdentitySettings.from(environment); }
    @Bean IdentityVerificationClient identityVerification(IdentitySettings settings, ObjectMapper mapper) {
        return new IdentityVerificationClient(settings, mapper);
    }
    @Bean JwtDecoder domainDecoder(IdentitySettings settings) {
        var client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client); factory.setReadTimeout(settings.readTimeout());
        var decoder = NimbusJwtDecoder.withJwkSetUri(settings.upstream() + "/oauth2/jwks")
                .restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(settings.issuer()));
        return decoder;
    }
    @Bean SecurityFilterChain domainSecurity(HttpSecurity http, JwtDecoder decoder, IdentitySettings settings,
                                               IdentityVerificationClient identity, AccountRepository accounts) throws Exception {
        return http.cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/status", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/content/**").access((authentication, request) -> {
                            var value = authentication.get();
                            return new AuthorizationDecision(value instanceof AnonymousAuthenticationToken || value.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_content")));
                        })
                        .requestMatchers("/api/v1/support").hasAuthority("SCOPE_support")
                        .requestMatchers("/api/v1/auth/me", "/api/v1/account-catalog", "/api/v1/author/previews/access", "/api/v1/plans", "/api/v1/plans/**").hasAuthority("SCOPE_account")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder)
                        .jwtAuthenticationConverter(new DomainTokenConverter(settings, identity, accounts)))
                        .authenticationEntryPoint((request, response, error) -> {
                            boolean unavailable = error instanceof AuthenticationServiceException;
                            response.setStatus(unavailable ? 503 : 401);
                            if (!unavailable) response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
                            response.getWriter().write(unavailable
                                    ? "{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}"
                                    : "{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to continue\"}");
                        })).build();
    }
}
