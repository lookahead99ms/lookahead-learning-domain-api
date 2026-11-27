package com.lookahead.domain.client;

import com.lookahead.domain.exception.AccountFailure;
import java.util.UUID;

/**
 * Identity side of a created test learner. One bean per environment (DLV-1016; it was a choice inside
 * {@code TestAccountCreationService}): the Local Identity operator in Local, {@link TestAccountNoSignInClient} for DEV's
 * database-only test accounts ({@code TestAccountModeService.cloud()}), {@link TestAccountSignInNotConfiguredClient} where
 * test accounts do not exist. The two conditions are in {@code config.AccountSignInConfiguration}.
 */
public interface TestAccountSignInClient {
    /** Creates the sign-in. Returns the provider subject in cloud mode, null for the Local Identity service. */
    String provision(UUID accountId, String username, String password);
    void activate(UUID accountId, String username);
    void disable(UUID accountId, String username);
    /** What every implementation answers when the sign-in side did not confirm a call. */
    static AccountFailure unavailable() {
        return new AccountFailure(503, "TEST_IDENTITY_UNAVAILABLE", "Identity did not confirm test-account maintenance. Retry the same action.");
    }
}
