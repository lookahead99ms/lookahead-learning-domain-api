package com.lookahead.domain.review.platform;

import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlatformReviewControllerTest {
    static final String ROOT = "/api/v1/platform-reviews";
    static final String ITEM = "{\"category\":\"problem\",\"page\":\"Search\",\"pageUrl\":\"/search\",\"title\":\"Search misses Kafka\",\"details\":\"Typed kafka, got nothing\",\"priority\":\"high\"}";
    @TempDir Path root;
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "author@lookahead.test", "Owner", true);
    final AccountPrincipal reviewer = new AccountPrincipal(UUID.randomUUID(), "reviewer@example.test", "Reviewer", true);
    AccountPrincipal actor;
    MockMvc http;

    @BeforeEach void setup() {
        actor = reviewer;
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        var mapper = JsonMapper.builder().build();
        var service = new PlatformReviewService(new FileSystemPlatformReviewObjects(root), author, mapper, "reviewer@example.test");
        http = MockMvcBuilders.standaloneSetup(new PlatformReviewController(service, mapper))
                .setControllerAdvice(new AccountErrorHandler(), new PlatformReviewUploadErrors())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == AccountPrincipal.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory b) { return actor; }
                }).build();
    }

    @Test void createListReadAndFetchAnImage() throws Exception {
        String key = UUID.randomUUID().toString();
        var created = http.perform(multipart(ROOT).file(item(ITEM)).file(image("shot.png", "image/png", PlatformReviewTestImages.png(true)))
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.title").value("Search misses Kafka"))
                .andExpect(jsonPath("data.status").value("new"))
                .andExpect(jsonPath("data.attachments[0].name").value("shot.png"))
                .andExpect(jsonPath("data.attachments[0].path").doesNotExist())
                .andExpect(jsonPath("data.reviewerAccountId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = JsonMapper.builder().build().readTree(created).path("data").path("id").asString();
        String image = JsonMapper.builder().build().readTree(created).path("data").path("attachments").path(0).path("id").asString();

        http.perform(multipart(ROOT).file(item(ITEM)).file(image("shot.png", "image/png", PlatformReviewTestImages.png(true)))
                .header("Idempotency-Key", key)).andExpect(status().isOk()).andExpect(jsonPath("data.id").value(id));
        http.perform(get(ROOT)).andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].attachmentCount").value(1))
                .andExpect(jsonPath("data.items[0].details").doesNotExist())
                .andExpect(jsonPath("data.nextBefore").isEmpty());
        http.perform(get(ROOT + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("data.details").value("Typed kafka, got nothing"));
        http.perform(get(ROOT + "/" + id + "/attachments/" + image)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        http.perform(get(ROOT + "/me")).andExpect(jsonPath("data.role").value("reviewer"));
    }

    @Test void updateTriageAndDelete() throws Exception {
        String id = create();
        String update = ITEM.replace("}", ",\"keepAttachmentIds\":[]}").replace("Search misses Kafka", "Search misses Kafka topics");
        http.perform(multipart(HttpMethod.PUT, ROOT + "/" + id).file(item(update)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("data.title").value("Search misses Kafka topics"));
        http.perform(put(ROOT + "/" + id + "/triage").contentType("application/json").content("{\"status\":\"accepted\",\"ownerNote\":\"\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("PLATFORM_REVIEW_ACCESS_DENIED"));
        actor = owner;
        http.perform(put(ROOT + "/" + id + "/triage").contentType("application/json").content("{\"status\":\"accepted\",\"ownerNote\":\"Thanks\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.ownerNote").value("Thanks"));
        actor = reviewer;
        http.perform(delete(ROOT + "/" + id)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("PLATFORM_REVIEW_LOCKED"));
        String other = create();
        http.perform(delete(ROOT + "/" + other)).andExpect(status().isNoContent());
        http.perform(get(ROOT + "/" + other)).andExpect(status().isNotFound());
    }

    @Test void malformedRequestsAreRejectedBeforeSaving() throws Exception {
        http.perform(multipart(ROOT).file(item(ITEM))).andExpect(status().isUnprocessableEntity());
        http.perform(multipart(ROOT).file(item(ITEM.replace("}", ",\"reviewerId\":\"forged\"}"))).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());
        http.perform(multipart(ROOT).file(item(ITEM)).file(image("x", "image/png", new byte[]{1})).file(new MockMultipartFile("extra", new byte[]{1}))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity());
        http.perform(multipart(ROOT).file(item(ITEM)).file(image("fake.png", "image/png", PlatformReviewTestImages.jpegWithExif()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("message").value("An image's contents do not match its file type."));
        http.perform(put(ROOT + "/" + UUID.randomUUID() + "/triage").contentType("application/json").content("{\"status\":\"done\"}"))
                .andExpect(status().isUnprocessableEntity());
        http.perform(get(ROOT + "/not-a-uuid")).andExpect(status().isNotFound());
        http.perform(get(ROOT)).andExpect(jsonPath("data.items.length()").value(0));
    }

    private String create() throws Exception {
        var body = http.perform(multipart(ROOT).file(item(ITEM)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body).path("data").path("id").asString();
    }

    private static MockMultipartFile item(String json) {
        return new MockMultipartFile("item", "blob", "application/json", json.getBytes());
    }

    private static MockMultipartFile image(String name, String type, byte[] bytes) {
        return new MockMultipartFile("image", name, type, bytes);
    }
}
