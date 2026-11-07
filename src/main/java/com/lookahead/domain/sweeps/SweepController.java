package com.lookahead.domain.sweeps;

import com.lookahead.domain.DevOnly;
import com.lookahead.domain.review.platform.PlatformReviewService;
import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DLV-971 (owner, 2026-11-06): {@code POST /api/v1/sweeps/run} {@code {"job": "support-requests" | "delivery-inbox" |
 * "admissions" | "all"}}, the owner's "run this job now". The Gateway adds the session, CSRF check and bearer token. The
 * owner alone (the same check as a triage of a review): 401 without a sign-in, {@code 403 PLATFORM_REVIEW_ACCESS_DENIED}
 * for everyone else, admins included; {@code 400 SWEEP_JOB_INVALID} for any other body; {@code 409 SWEEP_NOT_AVAILABLE}
 * for {@code admissions} where there is no cleanup (local mode). DEV and local only (owner, 2026-11-07): 404 {@code DEV_ONLY} in prod
 * ({@link DevOnly}); the 4-hour schedule itself runs everywhere.
 */
@RestController
@Profile("accounts")
@RequestMapping("/api/v1/sweeps")
public class SweepController {
    private final RuntimeSweeps sweeps;
    private final PlatformReviewService reviews;
    private final Environment environment;

    public SweepController(RuntimeSweeps sweeps, PlatformReviewService reviews, Environment environment) {
        this.sweeps = sweeps; this.reviews = reviews; this.environment = environment;
    }

    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RuntimeSweeps.Result> run(@AuthenticationPrincipal AccountPrincipal actor,
                                                 @RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
        DevOnly.require(environment, "Running a sweep by hand");
        reviews.requireOwner(actor);
        RuntimeSweeps.Job job = body != null && Set.of("job").equals(body.keySet()) && body.get("job") instanceof String name ? RuntimeSweeps.Job.named(name) : null;
        if (job == null) throw new AccountFailure(400, "SWEEP_JOB_INVALID", "Choose a job: support-requests, delivery-inbox, admissions or all.");
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(sweeps.runNow(actor.accountId(), job));
    }
}
