package com.lookahead.domain.client;

/**
 * The owner's changes to one user of the sign-in pool: disable, enable, delete. DEV and PROD have the pool
 * ({@link SignInCognitoClient}); Local has no pool and no bean of this type, and {@code AccountRemovalService} refuses
 * there before anything is stored.
 */
public interface SignInPoolClient {
    /** No sign-in with email or Google, and every refresh token of the user is revoked. */
    void disable(String username);
    void enable(String username);
    /** The user is gone; signing in again with the same email or Google account creates a new user with a new subject. */
    void remove(String username);
}
