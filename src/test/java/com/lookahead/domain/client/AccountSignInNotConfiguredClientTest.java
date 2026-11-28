package com.lookahead.domain.client;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * DLV-1016: outside Local the Identity operator is no bean any more. The two stand-ins give the
 * answer it gave there without a connection: one upstream line with OPERATOR_NOT_CONFIGURED under
 * the operation's name, then 503.
 */
class AccountSignInNotConfiguredClientTest {
  private static void refuses(String operation, ThrowingCallable call) {
    try (var log = LogCapture.start()) {
      assertThatThrownBy(call)
          .isInstanceOfSatisfying(
              AccountFailure.class,
              refused -> {
                assertThat(refused.status()).isEqualTo(503);
                assertThat(refused.code()).isEqualTo("TEST_IDENTITY_UNAVAILABLE");
                assertThat(refused.getMessage())
                    .isEqualTo(
                        "Identity did not confirm test-account maintenance. Retry the same"
                            + " action.");
              });
      assertThat(log.lines())
          .singleElement()
          .satisfies(line -> {
            assertThat(line.getLoggerName()).isEqualTo("lookahead.upstream");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage())
                .matches(
                    "Identity TestAccount\\."
                        + operation
                        + " refused in \\d+ ms: OPERATOR_NOT_CONFIGURED");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "OPERATOR_NOT_CONFIGURED");
          });
    }
  }

  @Test
  void disablingARealSignInAnswersAsTheOperatorWithoutAConnectionDid() {
    AccountSignInClient client = new AccountSignInNotConfiguredClient();
    refuses("disable-real", () -> client.disableReal(UUID.randomUUID()));
  }

  @Test
  void everyTestAccountCallAnswersAsTheOperatorWithoutAConnectionDid() {
    TestAccountSignInClient client = new TestAccountSignInNotConfiguredClient();
    UUID id = UUID.randomUUID();
    refuses(
        "provision",
        () ->
            client.provision(
                id, "test-0123456789abcdef01234567", "synthetic-learner-fixture-only"));
    refuses("activate", () -> client.activate(id, "test-0123456789abcdef01234567"));
    refuses("disable", () -> client.disable(id, "test-0123456789abcdef01234567"));
  }

  @Test
  void devTestAccountsHaveNothingToCall() {
    TestAccountSignInClient client = new TestAccountNoSignInClient();
    UUID id = UUID.randomUUID();
    try (var log = LogCapture.start()) {
      assertThat(client.provision(id, "test-0123456789@lookahead.dev", null))
          .isEqualTo(id.toString());
      client.activate(id, "test-0123456789@lookahead.dev");
      client.disable(id, "test-0123456789@lookahead.dev");
      assertThat(log.lines()).isEmpty();
    }
  }
}
