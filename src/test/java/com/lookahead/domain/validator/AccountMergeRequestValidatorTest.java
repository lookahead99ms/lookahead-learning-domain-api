package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-1016: the body check of an account merge, moved here from AccountMergeController. */
class AccountMergeRequestValidatorTest {
    static final String ID = "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d";
    final AccountMergeRequestValidator shapes = new AccountMergeRequestValidator();

    @Test void theAccountToKeepIsAUuidInEitherCase() {
        assertThat(shapes.keepAccountId(Map.of("keepAccountId", ID))).isEqualTo(UUID.fromString(ID));
        assertThat(shapes.keepAccountId(Map.of("keepAccountId", ID.toUpperCase(Locale.ROOT)))).isEqualTo(UUID.fromString(ID));
    }
    @Test void anythingElseIsRefusedWithTheOneSentence() {
        var nullId = new HashMap<String, Object>(); nullId.put("keepAccountId", null);
        for (Object body : Arrays.asList(null, List.of(), "bad", 7, Map.of(), Map.of("unknown", true), Map.of("keepAccountId", "nope"), Map.of("keepAccountId", 7),
                Map.of("keepAccountId", ID, "x", 1), Map.of("keepAccountId", "0-0-0-0-0"), Map.of("keepAccountId", ID.substring(0, 35)), nullId))
            assertThatThrownBy(() -> shapes.keepAccountId(body)).isInstanceOfSatisfying(AccountFailure.class, failure -> {
                assertThat(failure.status()).isEqualTo(400); assertThat(failure.code()).isEqualTo("INVALID_MERGE_REQUEST"); assertThat(failure).hasMessage("Send keepAccountId only.");
            });
    }
}
