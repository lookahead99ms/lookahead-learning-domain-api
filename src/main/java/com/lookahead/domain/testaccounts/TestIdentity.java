package com.lookahead.domain.testaccounts;

import java.util.UUID;

/** Identity side of a created test learner; {@link CreatedTestAccounts} picks the mode from {@link TestAccountPolicy}. */
public interface TestIdentity {
    /** Creates the sign-in. Returns the provider subject in cloud mode, null for the Local Identity service. */
    String provision(UUID accountId, String username, String password);
    void activate(UUID accountId, String username);
    void disable(UUID accountId, String username);
}
