package com.lookahead.domain.controller;

import com.lookahead.domain.service.review.PlatformReviewService;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.validator.PlatformReviewRequestValidator;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import static com.lookahead.domain.model.PlatformReviewModels.*;

/** DLV-928: /api/v1/platform-reviews. The Gateway adds the session, CSRF check and bearer token. DLV-971: ids are REV-/SUP- numbers, plus promote. */
@RestController
@Profile("accounts")
@RequestMapping("/api/v1/platform-reviews")
public class PlatformReviewController {
    private final PlatformReviewService reviews;
    private final PlatformReviewRequestValidator shapes;

    public PlatformReviewController(PlatformReviewService reviews, PlatformReviewRequestValidator shapes) {
        this.reviews = reviews; this.shapes = shapes;
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
        var body = shapes.newItem(request);
        var receipt = reviews.create(actor, shapes.key(key), shapes.fields(body), shapes.uploads(request));
        return ResponseEntity.status(receipt.replayed() ? 200 : 201).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(receipt.item()));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Item> update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, MultipartHttpServletRequest request,
            HttpServletResponse response) {
        var body = shapes.changedItem(request);
        var keep = shapes.keepAttachmentIds(body);
        noStore(response);
        return ApiResponse.success(reviews.update(actor, id, shapes.key(key), shapes.fields(body), keep, shapes.uploads(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id) {
        reviews.delete(actor, id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PutMapping(value = "/{id}/triage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Item> triage(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
                                    @RequestBody Map<String, Object> body, HttpServletResponse response) {
        var triage = shapes.triage(body);
        noStore(response);
        return ApiResponse.success(reviews.triage(actor, id, triage.status(), triage.ownerNote()));
    }

    /**
     * DLV-971: a ticket on the delivery plan from this item; 409 PLATFORM_REVIEW_ALREADY_PROMOTED when it has one.
     * Owner decision 2026-11-07: promoting writes the DEV plan, so it is DEV and local only (404 {@code DEV_ONLY} in prod).
     */
    @PostMapping("/{id}/promote")
    public ResponseEntity<ApiResponse<Promoted>> promote(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id) {
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.success(reviews.promote(actor, id)));
    }

    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
