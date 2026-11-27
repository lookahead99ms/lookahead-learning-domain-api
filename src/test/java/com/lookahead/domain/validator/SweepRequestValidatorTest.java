package com.lookahead.domain.validator;

import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.exception.AccountFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-1016: the body check of "run this job now", moved here from SweepController. */
class SweepRequestValidatorTest {
    final SweepRequestValidator shapes = new SweepRequestValidator();

    @Test void aKnownJobNameIsItsJob() {
        for (String name : List.of("support-requests", "delivery-inbox", "admissions", "maintenance", "all"))
            assertThat(shapes.job(Map.of("job", name))).isNotNull().isEqualTo(SweepJob.named(name));
    }
    @Test void anyOtherBodyIsRefusedWithTheListOfJobs() {
        var nullJob = new HashMap<String, Object>(); nullJob.put("job", null);
        for (Map<String, Object> body : Arrays.<Map<String, Object>>asList(null, Map.of(), Map.of("job", "nope"), Map.of("job", 7), Map.of("job", "all", "x", 1), nullJob))
            assertThatThrownBy(() -> shapes.job(body)).isInstanceOfSatisfying(AccountFailure.class, failure -> {
                assertThat(failure.status()).isEqualTo(400); assertThat(failure.code()).isEqualTo("SWEEP_JOB_INVALID");
                assertThat(failure).hasMessage("Choose a job: support-requests, delivery-inbox, admissions, maintenance or all.");
            });
    }
}
