package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.platform.security.PlatformSecurityConfiguration;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthorPreviewAccessControllerTest {
    private static final String PATH = "/api/v1/author/previews/access";
    private AnnotationConfigWebApplicationContext context;
    private AccountRepository accounts;
    private MockMvc http;

    @BeforeEach void configure() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("accounts", "local-test", "resource");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("author-preview-test",
                Map.of("app.deployment-environment", "local", "app.local-test.author-enabled", "true",
                        "app.identity.issuer", "http://127.0.0.1:4380", "app.identity.upstream", "http://identity:8080",
                        "app.identity.gateway-client-id", "lookahead-web-gateway",
                        "app.identity.verifier-secret", "synthetic-verifier-secret-for-unit-tests")));
        context.register(TestApplication.class);
        context.refresh();
        accounts = context.getBean(AccountRepository.class);
        when(accounts.findTopicGrants(LocalAuthorAccess.ACCOUNT_ID)).thenReturn(Set.of("learn:dsa"));
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void signedOutRequestRequiresBearerAuthentication() throws Exception {
        http.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        verifyNoInteractions(accounts);
    }

    @Test void scopedAuthorReceivesOnlyNoncacheableNoContent() throws Exception {
        http.perform(get(PATH).with(identity(author(), "SCOPE_account")))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void learnerCannotSelectAuthorIdentityInRequest() throws Exception {
        var learner = new AccountPrincipal(UUID.randomUUID(), "learner01", "Author", true);
        http.perform(get(PATH).param("author", "true").header("X-Author", "true")
                        .with(identity(learner, "SCOPE_account")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHOR_PREVIEW_ACCESS_DENIED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(accounts);
    }

    @Test void authorStillNeedsAccountTokenScope() throws Exception {
        http.perform(get(PATH).with(identity(author(), "SCOPE_content")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }

    @Test void expiredOrRevokedCatalogGrantIsDenied() throws Exception {
        when(accounts.findTopicGrants(LocalAuthorAccess.ACCOUNT_ID)).thenReturn(Set.of());
        http.perform(get(PATH).with(identity(author(), "SCOPE_account")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHOR_PREVIEW_ACCESS_DENIED"));
    }

    @Test void grantStorageFailureCannotAuthorizeAccess() throws Exception {
        when(accounts.findTopicGrants(LocalAuthorAccess.ACCOUNT_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic outage"));
        http.perform(get(PATH).with(identity(author(), "SCOPE_account")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_STORAGE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static RequestPostProcessor identity(AccountPrincipal principal, String authority) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority(authority))));
    }

    private static AccountPrincipal author() {
        return new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, LocalAuthorAccess.USERNAME,
                "Synthetic Author", true);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({PlatformSecurityConfiguration.class, AuthorPreviewAccessController.class,
            AuthorPreviewAccessService.class, LocalAuthorAccess.class, AccountErrorHandler.class})
    static class TestApplication {
        @Bean AccountRepository accounts() {
            return mock(AccountRepository.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        }
        @Bean @org.springframework.context.annotation.Primary JwtDecoder decoder() {
            return mock(JwtDecoder.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean SnapshotValidator catalog() {
            var mapper = new ObjectMapper();
            var catalog = mapper.createObjectNode();
            catalog.put("schemaVersion", "account-catalog/v1");
            catalog.put("catalogVersion", "sha256:" + "a".repeat(64));
            catalog.putArray("algorithmVersions");
            catalog.putArray("rankingVersions");
            var record = catalog.putArray("records").addObject()
                    .put("id", "synthetic-lesson").put("contentType", "lesson");
            record.putArray("topicIds").add("learn:dsa");
            record.putArray("route").add("learn").add("synthetic-lesson");
            return new SnapshotValidator(mapper, catalog);
        }
    }
}
