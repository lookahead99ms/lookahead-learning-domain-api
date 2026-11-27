package com.lookahead.domain.controller;

import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.job.SweepRunner;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DLV-971 (owner, 2026-11-06): {@code POST /api/v1/sweeps/run} {@code {"job": "support-requests" | "delivery-inbox" |
 * "admissions" | "maintenance" | "all"}}, the owner's "run this job now" ({@code maintenance}, DLV-970: the registered
 * store maintenance jobs; it answers an empty list where nothing is registered). The Gateway adds the session, CSRF check and bearer token. The
 * owner alone (the same check as a triage of a review): 401 without a sign-in, {@code 403 PLATFORM_REVIEW_ACCESS_DENIED}
 * for everyone else, admins included; {@code 400 SWEEP_JOB_INVALID} for any other body; {@code 409 SWEEP_NOT_AVAILABLE}
 * for {@code admissions} where there is no cleanup (local mode). DEV and local only (owner, 2026-11-07): 404 {@code DEV_ONLY} in prod
 * ({@code DevOnly}); the 4-hour schedule itself runs everywhere.
 */
@RestController
@Profile("accounts")
@RequestMapping("/api/v1/sweeps")
public class SweepController {
    private final SweepRunner sweeps;

    public SweepController(SweepRunner sweeps) { this.sweeps = sweeps; }

    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SweepRunner.Result> run(@AuthenticationPrincipal AccountPrincipal actor,
                                                 @RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
        // The three refusals (DEV only, owner only, the body) are answered by the runner, in that order (DLV-1016). The
        // header is set once they have passed and before the job runs, as it always was: a refusal by the job itself carries it.
        SweepJob job = sweeps.requested(actor, body);
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(sweeps.runNow(actor.accountId(), job));
    }
}
