package com.lookahead.domain.service.signin;

import com.lookahead.domain.client.SignInPasswordClient;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.SignInIdentity;
import java.util.function.Supplier;

/**
 * A signed-in person changes their own password. The rules are checked first; then SignInService
 * ends the application's sign-ins around the password change at the provider; last, the provider
 * signs the person out everywhere.
 */
public class SignInPasswordService {
  private final SignInService signInService;
  private final SignInPasswordClient cognito;

  public SignInPasswordService(SignInService signInService, SignInPasswordClient cognito) {
    this.signInService = signInService;
    this.cognito = cognito;
  }

  /** {@code accessToken} is asked for only once the rules have passed, as before. */
  public void change(
      SignInIdentity identity,
      String proof,
      String old,
      String next,
      Supplier<String> accessToken) {
    if (old.isEmpty()
        || old.length() > 128
        || next.codePointCount(0, next.length()) < 15
        || next.codePointCount(0, next.length()) > 128)
      throw new AccountFailure(
          422, "INVALID_PASSWORD", "Use a password with 15 to 128 characters.");
    if (next.equals(old))
      throw new AccountFailure(422, "PASSWORD_UNCHANGED", "Choose a different password.");
    String token = accessToken.get();
    signInService.changeCredentials(
        identity, proof, () -> cognito.changePassword(token, old, next));
    // Commit Domain revocation before the second provider operation: a provider
    // sign-out outage must not roll back the application's durable revocation.
    cognito.globalSignOut(token);
  }
}
