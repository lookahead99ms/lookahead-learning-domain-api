package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.AuthorReviewModels.Decision;
import com.lookahead.domain.model.AuthorReviewModels.Submission;
import java.util.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-1016: the request-shape checks of the author review routes, moved here from AuthorReviewController. */
class AuthorReviewRequestValidatorTest {
    static final String ID = "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d";
    final AuthorReviewRequestValidator shapes = new AuthorReviewRequestValidator();

    private static Map<String, Object> body(Object... changes) {
        var body = new HashMap<String, Object>(Map.of("artifactVersion", "v1", "contentHash", "h", "ticketId", "DLV-1", "decision", "APPROVE", "comment", "ok"));
        for (int i = 0; i < changes.length; i += 2) body.put((String) changes[i], changes[i + 1]);
        return body;
    }

    @Test void aCursorIsOptionalAndAKeyIsRequiredBothCanonicalUuids() {
        assertThat(shapes.cursor(null)).isNull();
        assertThat(shapes.cursor(ID)).isEqualTo(UUID.fromString(ID));
        assertThat(shapes.key(ID)).isEqualTo(UUID.fromString(ID));
        for (String wrong : List.of(ID.toUpperCase(Locale.ROOT), "nope", "", ID.substring(0, 35))) { invalid(() -> shapes.cursor(wrong)); invalid(() -> shapes.key(wrong)); }
        invalid(() -> shapes.key(null));
    }
    @Test void aDecisionIsTheFiveFieldsAndAnOptionalSupersededEvent() {
        assertThat(shapes.submission(null, body())).isEqualTo(new Submission("v1", "h", "DLV-1", Decision.APPROVE, "ok", null));
        assertThat(shapes.submission(null, body("supersedesEventId", null)).supersedesEventId()).isNull();
        assertThat(shapes.submission(null, body("decision", "NEED_MORE", "supersedesEventId", ID)))
                .isEqualTo(new Submission("v1", "h", "DLV-1", Decision.NEED_MORE, "ok", UUID.fromString(ID)));
    }
    @Test void aWrongDecisionBodyIsRefusedWithTheOneSentence() {
        invalid(() -> shapes.submission("x=1", body()));
        invalid(() -> shapes.submission("", body()));
        invalid(() -> shapes.submission(null, null));
        invalid(() -> shapes.submission(null, Map.of()));
        var missing = body(); missing.remove("comment");
        invalid(() -> shapes.submission(null, missing));
        for (Map<String, Object> wrong : List.of(body("extra", 1), body("decision", "approve"), body("decision", "NOPE"), body("decision", 7), body("decision", null),
                body("comment", 7), body("artifactVersion", 7), body("contentHash", null), body("ticketId", List.of("x")),
                body("supersedesEventId", "nope"), body("supersedesEventId", 7), body("supersedesEventId", ID.toUpperCase(Locale.ROOT))))
            invalid(() -> shapes.submission(null, wrong));
    }
    private static void invalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(422); assertThat(failure.code()).isEqualTo("INVALID_REVIEW_REQUEST"); assertThat(failure).hasMessage("Review request is invalid");
        });
    }
}
