package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import com.lookahead.learning.content.service.ProtectedContentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC + allowlist + file/hash/access service; no server, database or container. */
class StudyPlanPublicationBoundaryTest {
    private static final String INDEX = "/content/study-plans/index.json";
    private static final String DETAIL = "/content/study-plans/templates/synthetic-api-revision-d7-h1.json";
    @TempDir Path root;
    private final JsonMapper mapper = new JsonMapper();
    private final AccountRepository accounts = mock(AccountRepository.class,
            withSettings().mockMaker(MockMakers.SUBCLASS));
    private final AccountPrincipal learner = new AccountPrincipal(new UUID(0, 1), "synthetic", "Synthetic", true);
    private AccountPrincipal principal;
    private MockMvc http;

    @BeforeEach void fixture() throws Exception {
        byte[] index = mapper.writeValueAsBytes(Map.of("schemaVersion", "study-plan-picker/v1",
                "paths", List.of(Map.of("id", "synthetic-api-revision"))));
        byte[] template;
        try (var stream = getClass().getResourceAsStream("/study-plans/synthetic-template.json")) {
            template = stream.readAllBytes();
        }
        write(INDEX, index); write(DETAIL, template);
        var manifest = Map.of("schemaVersion", "content-publication/v1", "version", "synthetic-plan-publication-v1",
                "assets", List.of(asset(INDEX, index, "public", List.of(), "any"),
                        asset(DETAIL, template, "pro", List.of("grow:sample-api", "learn:sample-java"), "all")));
        Path manifestPath = root.resolve("manifest.json");
        Files.writeString(manifestPath, mapper.writeValueAsString(manifest));
        var policy = new ProtectedContentPolicy(mapper, manifestPath.toString(), root.toString());
        var service = new ProtectedContentService(policy, accounts);
        http = MockMvcBuilders.standaloneSetup(new ProtectedContentController(service))
                .setControllerAdvice(new AccountErrorHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == AccountPrincipal.class;
                    }
                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory binder) { return principal; }
                }).build();
    }

    @Test void anonymousPickerUsesExistingPublicationEndpointWithoutAccountMutation() throws Exception {
        http.perform(get(INDEX)).andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.schemaVersion").value("study-plan-picker/v1"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Content-Publication", "synthetic-plan-publication-v1"));
        verifyNoInteractions(accounts);
    }

    @Test void templateRequiresEnabledIdentityAndEveryDeclaredCourseOnEveryRead() throws Exception {
        http.perform(get(DETAIL)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        principal = learner;
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("grow:sample-api"));
        http.perform(get(DETAIL)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTENT_SCOPE_REQUIRED"));
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("grow:sample-api", "learn:sample-java"));
        http.perform(get(DETAIL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("study-plan-template/v1"))
                .andExpect(jsonPath("$.templateId").value("synthetic-api-revision-d7-h1"))
                .andExpect(header().string("Cache-Control", "no-store, private"));
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("grow:sample-api"));
        http.perform(get(DETAIL)).andExpect(status().isForbidden());
        when(accounts.isEnabled(learner.accountId())).thenReturn(false);
        http.perform(get(DETAIL)).andExpect(status().isUnauthorized());
        verify(accounts, times(4)).isEnabled(learner.accountId());
        verify(accounts, times(3)).findTopicGrants(learner.accountId());
        verifyNoMoreInteractions(accounts);
    }

    @Test void unlistedFilesAndChangedPublishedBytesStayUnavailable() throws Exception {
        Files.writeString(root.resolve("private-note.json"), "{\"private\":true}");
        http.perform(get("/content/private-note.json")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));
        write(INDEX, "{\"changed\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        http.perform(get(INDEX)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONTENT_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(accounts);
    }

    private Map<String, Object> asset(String path, byte[] bytes, String tier, List<String> scopes, String scopeMatch)
            throws Exception {
        return Map.of("path", path, "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "mediaType", "application/json", "tier", tier, "scopes", scopes, "scopeMatch", scopeMatch, "contentIds", List.of());
    }
    private void write(String path, byte[] bytes) throws Exception {
        Path file = root.resolve(path.substring("/content/".length()));
        Files.createDirectories(file.getParent()); Files.write(file, bytes);
    }
}
