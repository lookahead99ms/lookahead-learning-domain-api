package com.lookahead.domain.validator;

import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.exception.AccountFailure;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The body of "run this job now": {@code {"job": "<name>"}} only, a known job. DLV-1016: moved word for word from SweepController. */
@Component
public class SweepRequestValidator {
    public SweepJob job(Map<String, Object> body) {
        SweepJob job = body != null && Set.of("job").equals(body.keySet()) && body.get("job") instanceof String name ? SweepJob.named(name) : null;
        if (job == null) throw new AccountFailure(400, "SWEEP_JOB_INVALID", "Choose a job: support-requests, delivery-inbox, admissions, maintenance or all.");
        return job;
    }
}
