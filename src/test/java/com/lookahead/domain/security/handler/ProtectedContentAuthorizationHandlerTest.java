package com.lookahead.domain.security.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * {@code /content/**}: open to callers who are not signed in, and to signed-in callers with the
 * content scope.
 */
class ProtectedContentAuthorizationHandlerTest {
  final ProtectedContentAuthorizationHandler handler = new ProtectedContentAuthorizationHandler();

  boolean granted(Authentication caller) {
    return handler.authorize(() -> caller, null).isGranted();
  }

  @Test
  void aCallerWhoIsNotSignedInMayAsk() {
    assertThat(
            granted(
                new AnonymousAuthenticationToken(
                    "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))))
        .isTrue();
  }

  @Test
  void aSignedInCallerNeedsTheContentScope() {
    assertThat(
            granted(
                UsernamePasswordAuthenticationToken.authenticated(
                    "learner", null, List.of(new SimpleGrantedAuthority("SCOPE_content")))))
        .isTrue();
    assertThat(
            granted(
                UsernamePasswordAuthenticationToken.authenticated(
                    "learner", null, List.of(new SimpleGrantedAuthority("SCOPE_account")))))
        .isFalse();
    assertThat(
            granted(UsernamePasswordAuthenticationToken.authenticated("learner", null, List.of())))
        .isFalse();
  }
}
