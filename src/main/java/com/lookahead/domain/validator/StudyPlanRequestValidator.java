package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.util.PayloadReaders;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The headers of a study plan change, and the one body field its route reads: the Idempotency-Key, the If-Match
 * revision of a delete, the state of a lifecycle change. DLV-1016: moved word for word from StudyPlanController. The
 * plan bodies themselves are checked by the plan service and its validators.
 */
@Component
public class StudyPlanRequestValidator {
    /** Required (400 {@code IDEMPOTENCY_KEY_REQUIRED}), then a canonical UUID (400 {@code INVALID_ID}). */
    public UUID idempotencyKey(String value) {
        if (value == null) {
            throw new AccountFailure(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        }
        return PayloadReaders.uuid(value);
    }

    /** The revision a delete expects, from {@code If-Match: "revision-N"}: 428 when missing, 400 when not of that form. */
    public long revision(String expected) {
        if (expected == null) {
            throw new AccountFailure(428, "REVISION_REQUIRED", "If-Match revision is required");
        }
        if (!expected.matches("\"revision-[1-9][0-9]*\"")) {
            throw new AccountFailure(400, "INVALID_REVISION", "Use If-Match: \"revision-N\"");
        }
        return Long.parseLong(expected.substring(10, expected.length() - 1));
    }

    /** The {@code state} a lifecycle body names, as text; empty when there is no body. Whether it is a valid state is the service's check. */
    public String lifecycleState(JsonNode body) { return body == null ? "" : body.path("state").asText(); }
}
