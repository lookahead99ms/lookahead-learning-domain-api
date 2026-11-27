package com.lookahead.domain.validator;

import com.lookahead.domain.util.CanonicalUuid;
import com.lookahead.domain.dto.PlatformReviewTriage;
import com.lookahead.domain.exception.AccountFailure;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.model.PlatformReviewModels.*;

/**
 * The shape of a request on {@code /api/v1/platform-reviews}: the multipart {@code item} part and its fields, the
 * {@code image} parts, the Idempotency-Key and the triage body. DLV-1016: moved word for word from
 * PlatformReviewController. What the fields may contain is {@link PlatformReviewInputValidator}'s.
 */
@Component
public class PlatformReviewRequestValidator {
    private static final int ITEM_PART_BYTES = 64 * 1024;
    private static final Set<String> CREATE_FIELDS = Set.of("category", "page", "pageUrl", "title", "details", "priority");
    private static final Set<String> UPDATE_FIELDS = Set.of("category", "page", "pageUrl", "title", "details", "priority", "keepAttachmentIds");
    private final ObjectMapper mapper;

    public PlatformReviewRequestValidator(ObjectMapper mapper) { this.mapper = mapper; }

    /** The {@code item} part of a new review item: exactly the six fields. */
    public Map<String, Object> newItem(MultipartHttpServletRequest request) { return item(request, CREATE_FIELDS); }

    /** The {@code item} part of a changed review item: the six fields and {@code keepAttachmentIds}. */
    public Map<String, Object> changedItem(MultipartHttpServletRequest request) { return item(request, UPDATE_FIELDS); }

    /** The ids of the images a change keeps: a list of strings. */
    public List<String> keepAttachmentIds(Map<String, Object> body) {
        if (!(body.get("keepAttachmentIds") instanceof List<?> keep) || keep.stream().anyMatch(value -> !(value instanceof String)))
            throw PlatformReviewInputValidator.invalid("Reload the item; its images changed.");
        return keep.stream().map(String.class::cast).toList();
    }

    public PlatformReviewTriage triage(Map<String, Object> body) {
        if (body == null || !Set.of("status", "ownerNote").equals(body.keySet())
                || !(body.get("status") instanceof String status) || !(body.get("ownerNote") instanceof String note))
            throw PlatformReviewInputValidator.invalid("Choose a status.");
        return new PlatformReviewTriage(status, note);
    }

    private Map<String, Object> item(MultipartHttpServletRequest request, Set<String> allowed) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()
                || !Set.of("item", "image").containsAll(request.getMultiFileMap().keySet()))
            throw PlatformReviewInputValidator.invalid("The item is incomplete.");
        var parts = request.getMultiFileMap().getOrDefault("item", List.of());
        if (parts.size() != 1 || parts.getFirst().getSize() > ITEM_PART_BYTES) throw PlatformReviewInputValidator.invalid("The item is incomplete.");
        try {
            Map<String, Object> body = mapper.readValue(parts.getFirst().getBytes(), Map.class);
            if (body == null || !allowed.equals(body.keySet())) throw PlatformReviewInputValidator.invalid("The item is incomplete.");
            return body;
        } catch (JacksonException | IOException malformed) {
            throw PlatformReviewInputValidator.invalid("The item is incomplete.");
        }
    }

    public Fields fields(Map<String, Object> body) {
        return new Fields(text(body, "category"), text(body, "page"), text(body, "pageUrl"), text(body, "title"),
                text(body, "details"), text(body, "priority"));
    }

    /** The {@code image} parts as uploads: at most six, each at most 5 MB. */
    public List<Upload> uploads(MultipartHttpServletRequest request) {
        var files = request.getMultiFileMap().getOrDefault("image", List.of());
        if (files.size() > PlatformReviewInputValidator.IMAGES_PER_ITEM)
            throw PlatformReviewInputValidator.tooManyImages();
        var uploads = new ArrayList<Upload>();
        for (MultipartFile file : files) {
            if (file.getSize() > PlatformReviewInputValidator.IMAGE_BYTES)
                throw PlatformReviewInputValidator.imageTooLarge();
            try { uploads.add(new Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes())); }
            catch (IOException unreadable) { throw PlatformReviewInputValidator.invalid("An image could not be read."); }
        }
        return uploads;
    }

    /** The Idempotency-Key: required, a UUID in its canonical form. */
    public UUID key(String value) {
        UUID id = CanonicalUuid.parse(value);
        if (id == null) throw new AccountFailure(422, "PLATFORM_REVIEW_INVALID", "Retry the request from the page.");
        return id;
    }

    private static String text(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof String value)) throw PlatformReviewInputValidator.invalid("The item is incomplete.");
        return value;
    }
}
