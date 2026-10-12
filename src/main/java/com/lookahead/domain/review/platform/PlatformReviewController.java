package com.lookahead.domain.review.platform;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.platform.PlatformReviewModels.*;

/** DLV-928: /api/v1/platform-reviews. The Gateway adds the session, CSRF check and bearer token. */
@RestController
@Profile("accounts")
@RequestMapping("/api/v1/platform-reviews")
public class PlatformReviewController {
    private static final int ITEM_PART_BYTES = 64 * 1024;
    private static final Set<String> CREATE_FIELDS = Set.of("category", "page", "pageUrl", "title", "details", "priority");
    private static final Set<String> UPDATE_FIELDS = Set.of("category", "page", "pageUrl", "title", "details", "priority", "keepAttachmentIds");
    private final PlatformReviewService reviews;
    private final ObjectMapper mapper;

    public PlatformReviewController(PlatformReviewService reviews, ObjectMapper mapper) {
        this.reviews = reviews; this.mapper = mapper;
    }

    @GetMapping("/me")
    public ApiResponse<Viewer> me(@AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
        noStore(response); return ApiResponse.success(reviews.viewer(actor));
    }

    @GetMapping
    public ApiResponse<Page> list(@AuthenticationPrincipal AccountPrincipal actor, @RequestParam(required = false) String before,
                                  HttpServletResponse response) {
        noStore(response); return ApiResponse.success(reviews.list(actor, before));
    }

    @GetMapping("/{id}")
    public ApiResponse<Item> item(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id, HttpServletResponse response) {
        noStore(response); return ApiResponse.success(reviews.item(actor, id));
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
                                             @PathVariable String attachmentId) {
        var content = reviews.attachment(actor, id, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(content.bytes());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Item>> create(@AuthenticationPrincipal AccountPrincipal actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, MultipartHttpServletRequest request) {
        var body = item(request, CREATE_FIELDS);
        var receipt = reviews.create(actor, uuid(key), fields(body), uploads(request));
        return ResponseEntity.status(receipt.replayed() ? 200 : 201).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(receipt.item()));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Item> update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, MultipartHttpServletRequest request,
            HttpServletResponse response) {
        var body = item(request, UPDATE_FIELDS);
        if (!(body.get("keepAttachmentIds") instanceof List<?> keep) || keep.stream().anyMatch(value -> !(value instanceof String)))
            throw PlatformReviewService.invalid("Reload the item; its images changed.");
        noStore(response);
        return ApiResponse.success(reviews.update(actor, id, uuid(key), fields(body),
                keep.stream().map(String.class::cast).toList(), uploads(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id) {
        reviews.delete(actor, id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PutMapping(value = "/{id}/triage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Item> triage(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
                                    @RequestBody Map<String, Object> body, HttpServletResponse response) {
        if (body == null || !Set.of("status", "ownerNote").equals(body.keySet())
                || !(body.get("status") instanceof String status) || !(body.get("ownerNote") instanceof String note))
            throw PlatformReviewService.invalid("Choose a status.");
        noStore(response);
        return ApiResponse.success(reviews.triage(actor, id, status, note));
    }

    private Map<String, Object> item(MultipartHttpServletRequest request, Set<String> allowed) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()
                || !Set.of("item", "image").containsAll(request.getMultiFileMap().keySet()))
            throw PlatformReviewService.invalid("The item is incomplete.");
        var parts = request.getMultiFileMap().getOrDefault("item", List.of());
        if (parts.size() != 1 || parts.getFirst().getSize() > ITEM_PART_BYTES) throw PlatformReviewService.invalid("The item is incomplete.");
        try {
            Map<String, Object> body = mapper.readValue(parts.getFirst().getBytes(), Map.class);
            if (body == null || !allowed.equals(body.keySet())) throw PlatformReviewService.invalid("The item is incomplete.");
            return body;
        } catch (JacksonException | IOException malformed) {
            throw PlatformReviewService.invalid("The item is incomplete.");
        }
    }

    private static Fields fields(Map<String, Object> body) {
        return new Fields(text(body, "category"), text(body, "page"), text(body, "pageUrl"), text(body, "title"),
                text(body, "details"), text(body, "priority"));
    }

    private static List<Upload> uploads(MultipartHttpServletRequest request) {
        var files = request.getMultiFileMap().getOrDefault("image", List.of());
        if (files.size() > PlatformReviewService.IMAGES_PER_ITEM) throw PlatformReviewService.invalid("Attach at most 6 images to one item.");
        var uploads = new ArrayList<Upload>();
        for (MultipartFile file : files) {
            if (file.getSize() > PlatformReviewService.IMAGE_BYTES) throw PlatformReviewService.tooLarge("Each image must be 5 MB or smaller.");
            try { uploads.add(new Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes())); }
            catch (IOException unreadable) { throw PlatformReviewService.invalid("An image could not be read."); }
        }
        return uploads;
    }

    private static String text(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof String value)) throw PlatformReviewService.invalid("The item is incomplete.");
        return value;
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new AccountFailure(422, "PLATFORM_REVIEW_INVALID", "Retry the request from the page.");
        }
    }

    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
