package com.lookahead.domain;

import com.lookahead.learning.content.exception.AccountFailure;
import org.springframework.core.env.Environment;

/**
 * Owner decision 2026-11-07: some operations exist on DEV and on a local Domain only, never in PROD: the manual sweep
 * run and the Delivery Plan inbox upload and sync. {@code app.deployment-environment} (LOOKAHEAD_ENVIRONMENT) is
 * {@code local}, {@code dev} or {@code prod}; in {@code prod} these routes answer 404 {@code DEV_ONLY} and nothing runs.
 */
public final class DevOnly {
    private DevOnly() {}

    public static boolean allowed(Environment environment) {
        return !"prod".equals(environment.getProperty("app.deployment-environment", "local"));
    }

    /** 404 {@code DEV_ONLY} in PROD; {@code what} names the operation in the message. */
    public static void require(Environment environment, String what) {
        if (allowed(environment)) return;
        Events.refused(Events.EVENT, "dev-only", "DEV_ONLY").addKeyValue("operation", what)
                .log("{} is a DEV and local operation; refused in prod", what);
        throw new AccountFailure(404, "DEV_ONLY", what + " exists on DEV and local only.");
    }
}
