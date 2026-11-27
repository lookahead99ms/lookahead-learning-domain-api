package com.lookahead.domain.db.store.review;

import com.lookahead.domain.model.PlatformReviewModels;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** The kind decides the counter: support requests have their own, every other kind (and null) numbers reviews. */
class PlatformReviewRecordNumberStoreTest {
    @Test void onlyTheRequestKindSelectsTheSupportRequestCounter() {
        assertThat(PlatformReviewRecordNumberStore.partition(PlatformReviewModels.KIND_REQUEST)).isEqualTo("SEQUENCE#support-request");
        for (String kind : new String[]{null, "review", "REQUEST", "other_sequence", "request'); DROP TABLE review_status_events; --"})
            assertThat(PlatformReviewRecordNumberStore.partition(kind)).as(kind).isEqualTo("SEQUENCE#review-record");
    }
    @Test void theInMemoryCounterNumbersEachKindFromOne() {
        var numbers = new PlatformReviewRecordNumberMemoryStore();
        assertThat(numbers.next("review")).isEqualTo(1); assertThat(numbers.next("review")).isEqualTo(2);
        assertThat(numbers.next(PlatformReviewModels.KIND_REQUEST)).isEqualTo(1);
    }
}
