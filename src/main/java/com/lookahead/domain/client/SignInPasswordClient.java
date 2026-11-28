package com.lookahead.domain.client;

/**
 * A signed-in person's own password change at the sign-in provider, with their access token. Exists
 * where the password service exists: DEV and PROD ({@link SignInCognitoClient}).
 */
public interface SignInPasswordClient {
  /** A wrong current password is 401, a refused new one 422, anything else 503. */
  void changePassword(String token, String old, String next);

  /** Ends every sign-in of the person at the provider; a failure is 503. */
  void globalSignOut(String token);
}
