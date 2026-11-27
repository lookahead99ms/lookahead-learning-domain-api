package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** DLV-1016: the header checks of a study plan change, moved here from StudyPlanController. */
class StudyPlanRequestValidatorTest {
    static final String ID = "0f6d1c0e-6c3a-4a4e-9d6a-2f8c1b7e5a10";
    final StudyPlanRequestValidator shapes = new StudyPlanRequestValidator();

    @Test void anIdempotencyKeyIsRequiredAndIsAUuid() {
        assertThat(shapes.idempotencyKey(ID)).isEqualTo(UUID.fromString(ID));
        refused(() -> shapes.idempotencyKey(null), 400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        refused(() -> shapes.idempotencyKey("nope"), 400, "INVALID_ID", "A canonical UUID is required");
        refused(() -> shapes.idempotencyKey(""), 400, "INVALID_ID", "A canonical UUID is required");
    }
    @Test void aDeleteNamesTheRevisionItExpectsInIfMatch() {
        assertThat(shapes.revision("\"revision-3\"")).isEqualTo(3);
        assertThat(shapes.revision("\"revision-9223372036854775807\"")).isEqualTo(Long.MAX_VALUE);
        refused(() -> shapes.revision(null), 428, "REVISION_REQUIRED", "If-Match revision is required");
        for (String wrong : new String[]{"revision-3", "\"revision-0\"", "\"revision-03\"", "\"revision-x\"", "", "W/\"revision-3\"", "\"revision-3\" "})
            refused(() -> shapes.revision(wrong), 400, "INVALID_REVISION", "Use If-Match: \"revision-N\"");
        // A revision of the right form that is no long: the number itself fails, as before, and the controller logs it as an invalid payload.
        assertThatThrownBy(() -> shapes.revision("\"revision-99999999999999999999\"")).isInstanceOf(NumberFormatException.class);
    }
    @Test void theLifecycleStateIsReadAsTextAndIsEmptyWithoutABody() {
        var mapper = new ObjectMapper();
        assertThat(shapes.lifecycleState(null)).isEmpty();
        assertThat(shapes.lifecycleState(mapper.readTree("{\"state\":\"archived\"}"))).isEqualTo("archived");
        assertThat(shapes.lifecycleState(mapper.readTree("{}"))).isEmpty();
        assertThat(shapes.lifecycleState(mapper.readTree("{\"state\":7}"))).isEqualTo("7");
        assertThat(shapes.lifecycleState(mapper.readTree("[]"))).isEmpty();
    }
    private static void refused(ThrowingCallable call, int status, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(status); assertThat(failure.code()).isEqualTo(code); assertThat(failure).hasMessage(message);
        });
    }
}
