package com.lookahead.domain.contract;

import com.lookahead.domain.DomainAccountController;
import com.lookahead.domain.cloud.CloudSignInController;
import com.lookahead.domain.cloud.CloudSignInRegistry;
import com.lookahead.domain.cloud.CognitoIdentity;
import com.lookahead.learning.content.controller.AuthorPreviewAccessController;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DLV-968 T13: provider side of the Gateway's consumer contract. The file is the Gateway's
 * {@code src/test/resources/contracts/domain-api-v1.json}; CI fetches it from the Gateway's newest calendar tag and
 * the committed copy serves local runs. Every (method, path) must be a real controller mapping, and the calls whose
 * response the Gateway interprets (sign-in admission, the cloud sign-in actions, the account check, the author preview
 * check) are driven through the real controllers with stubbed services so their status and the fields the Gateway
 * reads are proven on an actual response.
 */
class DomainProviderContractTest {
    static final Path CONTRACT = Path.of(System.getProperty("user.dir"), "src/test/resources/contracts/domain-api-v1.json");
    static final String SKIP = "Consumer contract absent: " + CONTRACT + " is fetched from the Gateway's newest v* tag in CI "
            + "(CROSS_REPO_READ_TOKEN); copy the Gateway's src/test/resources/contracts/domain-api-v1.json there to run this test locally.";
    static final String CLOUD = "/internal/v1/cloud-sign-ins/";
    static final Set<String> DRIVEN = Set.of("auth-me", "author-preview-access");
    static final UUID ACCOUNT = UUID.fromString("0f6d1c0e-6c3a-4a4e-9d6a-2f8c1b7e5a10"), SIGN_IN = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");
    static JsonNode contract;

    @BeforeAll static void requireContract() throws IOException {
        if (!Files.isRegularFile(CONTRACT)) System.out.println("SKIPPED DomainProviderContractTest: " + SKIP);
        Assumptions.assumeTrue(Files.isRegularFile(CONTRACT), SKIP);
        contract = JsonMapper.builder().build().readTree(Files.readString(CONTRACT));
        assertThat(contract.path("schema").asString()).isEqualTo("gateway-domain-contract/v1");
        assertThat(contract.path("provider").asString()).isEqualTo("lookahead99ms/lookahead-learning-domain-api");
    }

    /** Request-mapping detection of the real controller classes without instantiating them or their services. */
    static final class Mappings extends RequestMappingHandlerMapping {
        final List<String> routes = new ArrayList<>();
        final List<RequestMappingInfo> infos = new ArrayList<>();
        Mappings() {
            var context = new StaticWebApplicationContext();
            context.setServletContext(new MockServletContext());
            context.refresh();
            setApplicationContext(context);
            afterPropertiesSet();
            // Every production controller, whatever its profile or condition: the Gateway may call a route in any deployment.
            var scanner = new ClassPathScanningCandidateComponentProvider(false) {
                @Override protected boolean isCandidateComponent(MetadataReader reader) {
                    return reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName());
                }
            };
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
            for (var definition : scanner.findCandidateComponents("com.lookahead")) {
                Class<?> type;
                try { type = Class.forName(definition.getBeanClassName()); } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
                if (type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue; // test stubs
                for (Method method : type.getDeclaredMethods()) {
                    RequestMappingInfo info = getMappingForMethod(method, type);
                    if (info == null) continue;
                    infos.add(info);
                    routes.add(type.getSimpleName() + "." + method.getName() + " " + info);
                }
            }
        }
        List<RequestMappingInfo> matching(String method, String path, String contentType, boolean full) {
            var request = new MockHttpServletRequest(method, path);
            if (contentType != null) request.setContentType(contentType);
            ServletRequestPathUtils.parseAndCache(request);
            var found = new ArrayList<RequestMappingInfo>();
            for (RequestMappingInfo info : infos) {
                RequestMappingInfo match = full ? info.getMatchingCondition(request)
                        : info.getPathPatternsCondition() != null && info.getPathPatternsCondition().getMatchingCondition(request) != null
                            && info.getMethodsCondition().getMatchingCondition(request) != null ? info : null;
                if (match != null) found.add(match);
            }
            // Spring's own ordering: /platform-reviews/me beats /platform-reviews/{id}; only equally specific matches are ambiguous.
            found.sort((left, right) -> left.compareTo(right, request));
            return found.size() > 1 && found.get(0).compareTo(found.get(1), request) != 0 ? found.subList(0, 1) : found;
        }
    }

    @Test void everyContractRouteIsAControllerMappingAndDeferredRoutesStayUnmapped() {
        var mappings = new Mappings();
        assertThat(mappings.infos).hasSizeGreaterThan(50);
        var missing = new TreeSet<String>();
        var ambiguous = new TreeSet<String>();
        for (JsonNode call : contract.path("calls")) {
            String method = call.path("method").asString(), example = call.path("example").asString();
            String contentType = call.has("requestContentType") ? call.path("requestContentType").asString() : null;
            var found = mappings.matching(method, example, contentType, true);
            if (found.isEmpty()) missing.add(call.path("id").asString() + ": " + method + " " + example);
            if (found.size() > 1) ambiguous.add(call.path("id").asString() + ": " + found);
        }
        assertThat(missing).as("contract routes without a Domain controller mapping").isEmpty();
        assertThat(ambiguous).as("contract routes matching several mappings").isEmpty();
        var provided = new TreeSet<String>();
        for (JsonNode entry : contract.path("forwardedWithoutProvider")) {
            if (!mappings.matching(entry.path("method").asString(), entry.path("example").asString(), null, false).isEmpty())
                provided.add(entry.path("id").asString());
        }
        assertThat(provided).as("routes the contract records as unprovided but Domain now maps; move them to calls").isEmpty();
    }

    @Test void interpretedCallsAnswerAListedStatusWithTheFieldsTheGatewayReads() throws Exception {
        var http = driven();
        var verified = new TreeSet<String>();
        var expectedVerified = new TreeSet<String>();
        for (JsonNode call : contract.path("calls")) {
            String id = call.path("id").asString(), path = call.path("path").asString(), method = call.path("method").asString();
            boolean reads = !call.path("reads").isEmpty();
            if (reads) expectedVerified.add(id);
            if (!path.startsWith(CLOUD) && !DRIVEN.contains(id)) continue;
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), call.path("example").asString())
                    .header("Authorization", "Bearer synthetic-provider-token")
                    .header("X-LookAhead-Gateway-Secret", "synthetic-gateway-secret")
                    .header("X-LookAhead-SignIn-Proof", "synthetic-proof");
            if (call.has("exampleBody")) request = request.contentType(call.path("requestContentType").asString())
                    .content(call.path("exampleBody").toString().getBytes(StandardCharsets.UTF_8));
            var result = http.perform(request).andReturn().getResponse();
            var statuses = new ArrayList<Integer>();
            call.path("statuses").forEach(status -> statuses.add(status.asInt()));
            assertThat(statuses).as(id + " answered " + result.getStatus() + " " + result.getContentAsString()).contains(result.getStatus());
            if (result.getStatus() != 204) assertThat(result.getContentType()).as(id + " content type").startsWith("application/json");
            JsonNode body = result.getContentAsString().isEmpty() ? tools.jackson.databind.node.NullNode.getInstance() : JsonMapper.builder().build().readTree(result.getContentAsString());
            for (String field : fields(call.path("reads")))
                assertThat(body.at(field).isMissingNode()).as(id + " must answer " + field + " in " + body).isFalse();
            for (String field : fields(call.path("readsOptional")))
                if (!body.at(field).isMissingNode()) assertThat(body.at(field).isBoolean() || body.at(field).isNull()).as(id + " " + field).isTrue();
            verified.add(id);
        }
        assertThat(verified).as("every call whose fields the Gateway reads is driven through its controller").containsAll(expectedVerified);
        assertThat(verified).hasSizeGreaterThanOrEqualTo(16);
    }

    static List<String> fields(JsonNode names) {
        var pointers = new ArrayList<String>();
        names.forEach(name -> pointers.add("/" + name.asString().replace('.', '/')));
        return pointers;
    }

    /** The controllers whose answers the Gateway interprets, on stubbed services; no database, provider or network. */
    static MockMvc driven() {
        var identity = new CognitoIdentity("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example", "synthetic-subject", "synthetic-family",
                "learner@example.test", "Learner", Instant.parse("2026-10-31T00:00:00Z"));
        var principal = new AccountPrincipal(ACCOUNT, "learner@example.test", "Learner", true);
        var admission = new CloudSignInRegistry.Admission(ACCOUNT, SIGN_IN, null, Instant.parse("2026-10-31T00:10:00Z"));
        var entry = new CloudSignInRegistry.Entry(SIGN_IN, true, "Work laptop", "Synthetic browser", Instant.parse("2026-10-31T00:00:00Z"), Instant.parse("2026-10-31T00:05:00Z"));
        var registry = mock(CloudSignInRegistry.class);
        when(registry.admit(any(), any())).thenReturn(admission);
        when(registry.replace(any(), any(), any(), any())).thenReturn(new CloudSignInRegistry.Admission(ACCOUNT, SIGN_IN, null, null));
        when(registry.revoke(any(), any(), any())).thenReturn(false);
        when(registry.rename(any(), any(), anyString())).thenReturn(entry);
        when(registry.inventory(any(), any())).thenReturn(new CloudSignInRegistry.Inventory(2, List.of(entry)));
        when(registry.challenge(any(), any(), any())).thenReturn(new CloudSignInRegistry.ChallengeView(2, List.of(entry), Instant.parse("2026-10-31T00:10:00Z")));
        when(registry.profile(any(), any(), any())).thenReturn(Map.of("displayName", "Learner"));
        when(registry.preferences(any(), any(), anyString())).thenReturn(Map.of("theme", "dark"));
        when(registry.links(any(), any(), any())).thenReturn(Map.of("links", Map.of()));
        var cognito = mock(software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient.class);
        var account = new DomainAccountController(mock(AccountRepository.class), mock(ProtectedContentPolicy.class), mock(LocalAuthorAccess.class));
        var resolver = new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == CognitoIdentity.class || parameter.getParameterType() == AccountPrincipal.class;
            }
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request, WebDataBinderFactory binders) {
                return parameter.getParameterType() == CognitoIdentity.class ? identity : principal;
            }
        };
        return MockMvcBuilders.standaloneSetup(new CloudSignInController(registry, cognito), account,
                        new AuthorPreviewAccessController(mock(AuthorPreviewAccessService.class)))
                .setControllerAdvice(new AccountErrorHandler()).setCustomArgumentResolvers(resolver).build();
    }
}
