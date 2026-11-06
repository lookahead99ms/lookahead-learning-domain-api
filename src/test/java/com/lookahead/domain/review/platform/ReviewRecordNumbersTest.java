package com.lookahead.domain.review.platform;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReviewRecordNumbersTest {
    @Test void selectsOnlyTheTwoKnownSequencesAsBoundValues() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "review_record_seq")).thenReturn(12L);
        when(jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "support_request_seq")).thenReturn(34L);
        var numbers = new ReviewRecordNumbers.Jdbc(jdbc);
        assertThat(numbers.next("review")).isEqualTo(12L);
        assertThat(numbers.next("request")).isEqualTo(34L);
        verify(jdbc).queryForObject("SELECT nextval(?::regclass)", Long.class, "review_record_seq");
        verify(jdbc).queryForObject("SELECT nextval(?::regclass)", Long.class, "support_request_seq");
        verifyNoMoreInteractions(jdbc);
    }

    @Test void unrecognizedKindsCannotBecomeSqlOrSelectAnotherSequence() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "review_record_seq")).thenReturn(7L);
        var numbers = new ReviewRecordNumbers.Jdbc(jdbc);
        // Preserve the existing default-to-review behavior, including for null.
        for (String kind : new String[]{null, "REQUEST", "other_sequence", "request'); DROP TABLE review_status_events; --"})
            assertThat(numbers.next(kind)).isEqualTo(7L);
        verify(jdbc, times(4)).queryForObject("SELECT nextval(?::regclass)", Long.class, "review_record_seq");
        verifyNoMoreInteractions(jdbc);
    }

    @Test void nullDatabaseResultKeepsTheExistingZeroFallback() {
        var jdbc = mock(JdbcTemplate.class);
        assertThat(new ReviewRecordNumbers.Jdbc(jdbc).next("request")).isZero();
        verify(jdbc).queryForObject("SELECT nextval(?::regclass)", Long.class, "support_request_seq");
        verifyNoMoreInteractions(jdbc);
    }
}
