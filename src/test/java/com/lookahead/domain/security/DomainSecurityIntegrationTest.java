package com.lookahead.domain.security;

import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.domain.DomainStorageFailureFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DomainSecurityIntegrationTest {
    private static final String SUBJECT = "6c8bc359-1141-4c98-83e6-a5ca967fe180";
    private AnnotationConfigWebApplicationContext context;
    private AccountRepository accounts;
    private IdentityVerificationClient identity;
    private JwtDecoder decoder;
    private MockMvc http;

    @BeforeEach void configure() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("accounts", "resource");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("boundary-test", Map.of(
                "app.deployment-environment", "local", "app.identity.issuer", "http://127.0.0.1:4380",
                "app.identity.upstream", "http://identity:8080", "app.identity.gateway-client-id", "lookahead-web-gateway",
                "app.identity.verifier-secret", "synthetic-verifier-secret-for-unit-tests")));
        context.register(TestApplication.class); context.refresh();
        accounts = context.getBean(AccountRepository.class); identity = context.getBean(IdentityVerificationClient.class);
        decoder = context.getBean(JwtDecoder.class);
        when(decoder.decode("synthetic-token")).thenReturn(token("lookahead-web-gateway", "account"));
        when(identity.verify("synthetic-token")).thenReturn(new IdentityVerificationClient.Verification(true, SUBJECT, "learner", "Learner", "lookahead-web-gateway"));
        http = MockMvcBuilders.webAppContextSetup(context).addFilters(new DomainStorageFailureFilter()).apply(springSecurity()).build();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void authenticatedIdentityReachesResourceWithoutSessionOrPassword() throws Exception {
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("subject").value(SUBJECT)).andExpect(header().doesNotExist("Set-Cookie"));
        verify(accounts).ensureSubject(UUID.fromString(SUBJECT));
        verifyNoMoreInteractions(accounts);
    }
    @Test void invalidSignatureOrWrongClientIsUnauthorizedBeforeInternalLookup() throws Exception {
        when(decoder.decode("synthetic-token")).thenThrow(new BadJwtException("Invalid signature"));
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        reset(decoder); when(decoder.decode("synthetic-token")).thenReturn(token("other-client", "account"));
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(identity, accounts);
    }
    @Test void inactiveIdentityIs401WhileUnavailableAuthorityIs503() throws Exception {
        when(identity.verify("synthetic-token")).thenReturn(new IdentityVerificationClient.Verification(false, null, null, null, null));
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
        when(identity.verify("synthetic-token")).thenThrow(new IdentityUnavailableException());
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("IDENTITY_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("WWW-Authenticate"));
        verifyNoInteractions(accounts);
    }
    @Test void subjectStorageFailureIsSafe503AndNeverExecutesResource() throws Exception {
        doThrow(new DataAccessResourceFailureException("synthetic private database details"))
                .when(accounts).ensureSubject(UUID.fromString(SUBJECT));
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ACCOUNT_STORAGE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
    @Test void tokenScopeAndDenyByDefaultAreEnforced() throws Exception {
        when(decoder.decode("synthetic-token")).thenReturn(token("lookahead-web-gateway", "content"));
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer synthetic-token")).andExpect(status().isForbidden());
        http.perform(post("/api/v1/auth/register").header("Authorization", "Bearer synthetic-token")).andExpect(status().isForbidden());
        http.perform(post("/oauth2/token").header("Authorization", "Bearer synthetic-token")).andExpect(status().isForbidden());
    }
    @Test void anonymousPublicRoutesDoNotDependOnIdentityOrProductStorage() throws Exception {
        http.perform(get("/api/v1/status")).andExpect(status().isOk());
        http.perform(get("/content/public.json")).andExpect(status().isOk());
        http.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(identity, accounts, decoder);
    }
    private static Jwt token(String client, String scope) {
        return Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject(SUBJECT).issuer("http://127.0.0.1:4380")
                .audience(List.of("lookahead-api")).claim("client_id", client).claim("scope", scope)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({DomainSecurityConfiguration.class, ProbeController.class})
    static class TestApplication {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean AccountRepository accounts() { return mock(AccountRepository.class, withSettings().mockMaker(MockMakers.SUBCLASS)); }
        @Bean @Primary JwtDecoder decoder() { return mock(JwtDecoder.class, withSettings().mockMaker(MockMakers.SUBCLASS)); }
        @Bean @Primary IdentityVerificationClient verifier() { return mock(IdentityVerificationClient.class, withSettings().mockMaker(MockMakers.SUBCLASS)); }
    }
    @RestController static class ProbeController {
        @GetMapping("/api/v1/auth/me") Map<String, String> me(@AuthenticationPrincipal AccountPrincipal principal) { return Map.of("subject", principal.accountId().toString()); }
        @GetMapping({"/api/v1/status", "/content/public.json"}) Map<String, String> publicRoute() { return Map.of("status", "ok"); }
    }
}
