package com.lookahead.domain.client;

import java.util.UUID;

/**
 * Sign-in side of a real (not test) account where this service does not own the sign-in: Local, where the Identity
 * service does. The owner's delete of a learner disables the sign-in there before any data is erased. One bean per
 * environment ({@code config.AccountSignInConfiguration}): the Local Identity operator in Local, and
 * {@link AccountSignInNotConfiguredClient} everywhere else, where the service refuses before it calls.
 */
public interface AccountSignInClient {
    void disableReal(UUID accountId);
}
