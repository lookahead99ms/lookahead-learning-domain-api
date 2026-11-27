package com.lookahead.domain.validator;

import com.lookahead.domain.dto.PlatformReviewTriage;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.PlatformReviewModels.Fields;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** DLV-1016: the request-shape checks of /api/v1/platform-reviews, moved here from PlatformReviewController. */
class PlatformReviewRequestValidatorTest {
    static final String ITEM = "{\"category\":\"c\",\"page\":\"p\",\"pageUrl\":\"u\",\"title\":\"t\",\"details\":\"d\",\"priority\":\"high\"}";
    static final String CHANGED = ITEM.replace("}", ",\"keepAttachmentIds\":[\"a\",\"b\"]}");
    final PlatformReviewRequestValidator shapes = new PlatformReviewRequestValidator(new ObjectMapper());

    private static MockMultipartHttpServletRequest request(MockMultipartFile... parts) {
        var request = new MockMultipartHttpServletRequest();
        for (var part : parts) request.addFile(part);
        return request;
    }
    private static MockMultipartFile item(String json) { return new MockMultipartFile("item", "item.json", "application/json", json.getBytes(StandardCharsets.UTF_8)); }
    private static MockMultipartFile image(int bytes) { return new MockMultipartFile("image", "a.png", "image/png", new byte[bytes]); }

    @Test void theItemPartHoldsExactlyTheFieldsOfItsRoute() {
        assertThat(shapes.newItem(request(item(ITEM))).keySet()).containsExactlyInAnyOrder("category", "page", "pageUrl", "title", "details", "priority");
        assertThat(shapes.changedItem(request(item(CHANGED), image(3)))).containsEntry("keepAttachmentIds", List.of("a", "b"));
        incomplete(() -> shapes.newItem(request(item(CHANGED))));
        incomplete(() -> shapes.changedItem(request(item(ITEM))));
        incomplete(() -> shapes.newItem(request()));
        incomplete(() -> shapes.newItem(request(item(ITEM), item(ITEM))));
        incomplete(() -> shapes.newItem(request(item("{bad"))));
        incomplete(() -> shapes.newItem(request(item("null"))));
        incomplete(() -> shapes.newItem(request(item("[]"))));
        incomplete(() -> shapes.newItem(request(new MockMultipartFile("item", "i", "application/json", new byte[64 * 1024 + 1]))));
        incomplete(() -> shapes.newItem(request(item(ITEM), new MockMultipartFile("other", "o", "text/plain", new byte[1]))));
        var withQuery = request(item(ITEM)); withQuery.setQueryString("x=1");
        incomplete(() -> shapes.newItem(withQuery));
        var withParameter = request(item(ITEM)); withParameter.setParameter("x", "1");
        incomplete(() -> shapes.newItem(withParameter));
    }
    @Test void theFieldsAreSixStrings() {
        assertThat(shapes.fields(shapes.newItem(request(item(ITEM))))).isEqualTo(new Fields("c", "p", "u", "t", "d", "high"));
        incomplete(() -> shapes.fields(shapes.newItem(request(item(ITEM.replace("\"t\"", "7"))))));
        incomplete(() -> shapes.fields(shapes.newItem(request(item(ITEM.replace("\"high\"", "null"))))));
    }
    @Test void theKeptImagesAreAListOfStrings() {
        assertThat(shapes.keepAttachmentIds(Map.of("keepAttachmentIds", List.of("a", "b")))).containsExactly("a", "b");
        assertThat(shapes.keepAttachmentIds(Map.of("keepAttachmentIds", List.of()))).isEmpty();
        for (Map<String, Object> wrong : List.<Map<String, Object>>of(Map.of(), Map.of("keepAttachmentIds", "a"), Map.of("keepAttachmentIds", List.of(1)), Map.of("keepAttachmentIds", Arrays.asList("a", null))))
            refused(() -> shapes.keepAttachmentIds(wrong), 422, "PLATFORM_REVIEW_INVALID", "Reload the item; its images changed.");
    }
    @Test void atMostSixImagesOfAtMostFiveMegabytesEach() {
        var six = request(item(ITEM), image(1), image(2), image(3), image(4), image(5), image(6));
        assertThat(shapes.uploads(six)).hasSize(6).first().satisfies(upload -> {
            assertThat(upload.name()).isEqualTo("a.png"); assertThat(upload.declaredType()).isEqualTo("image/png"); assertThat(upload.bytes()).hasSize(1);
        });
        assertThat(shapes.uploads(request(item(ITEM)))).isEmpty();
        refused(() -> shapes.uploads(request(image(1), image(1), image(1), image(1), image(1), image(1), image(1))), 422, "PLATFORM_REVIEW_INVALID", "Attach at most 6 images to one item.");
        refused(() -> shapes.uploads(request(image(5 * 1024 * 1024 + 1))), 413, "PLATFORM_REVIEW_TOO_LARGE", "Each image must be 5 MB or smaller.");
        assertThat(shapes.uploads(request(image(5 * 1024 * 1024)))).hasSize(1);
    }
    @Test void theKeyIsACanonicalUuid() {
        String id = "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d";
        assertThat(shapes.key(id)).isEqualTo(UUID.fromString(id));
        for (String wrong : Arrays.asList(null, "nope", id.toUpperCase(Locale.ROOT), ""))
            refused(() -> shapes.key(wrong), 422, "PLATFORM_REVIEW_INVALID", "Retry the request from the page.");
    }
    @Test void aTriageIsAStatusAndANoteBothStrings() {
        assertThat(shapes.triage(Map.of("status", "open", "ownerNote", "n"))).isEqualTo(new PlatformReviewTriage("open", "n"));
        var nullNote = new HashMap<String, Object>(); nullNote.put("status", "open"); nullNote.put("ownerNote", null);
        for (Map<String, Object> wrong : Arrays.<Map<String, Object>>asList(null, Map.of(), Map.of("status", "open"), Map.of("status", 7, "ownerNote", "n"), Map.of("status", "open", "ownerNote", "n", "x", 1), nullNote))
            refused(() -> shapes.triage(wrong), 422, "PLATFORM_REVIEW_INVALID", "Choose a status.");
    }
    private static void incomplete(ThrowingCallable call) { refused(call, 422, "PLATFORM_REVIEW_INVALID", "The item is incomplete."); }
    private static void refused(ThrowingCallable call, int status, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(status); assertThat(failure.code()).isEqualTo(code); assertThat(failure).hasMessage(message);
        });
    }
}
