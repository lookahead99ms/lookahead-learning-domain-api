package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * DLV-1016: the merge email checks, moved from AccountMergeEmailConfigValues.from and publicUrl:
 * the same refusals in the same order.
 */
class AccountMergeEmailConfigValidatorTest {
  static final String FROM_NEEDED =
      "LOOKAHEAD_EMAIL_ENABLED=true needs LOOKAHEAD_EMAIL_FROM (app.email.from): an address, or a"
          + " name and <address>";
  static final String REGION_NEEDED =
      "LOOKAHEAD_EMAIL_ENABLED=true needs AWS_REGION (app.email.region) for SES";

  @Test
  void whileTheEmailIsOffNothingElseIsReadOrRefused() {
    for (String off : new String[] {null, "false", " FALSE ", "no", ""}) {
      var environment =
          new MockEnvironment()
              .withProperty("app.email.from", "not an address")
              .withProperty("app.email.region", "nowhere");
      if (off != null) environment.withProperty("app.email.enabled", off);
      var values = AccountMergeEmailConfigValidator.validated(environment);
      assertThat(values.enabled()).isFalse();
      assertThat(values.from()).isEmpty();
      assertThat(values.region()).isEmpty();
    }
  }

  /**
   * The sender address is judged before the region; each case makes one more leading setting right.
   */
  @Test
  void whenSeveralSettingsAreWrongTheRefusalsComeInOneFixedOrder() {
    var environment = new MockEnvironment().withProperty("app.email.enabled", " True ");
    assertThatThrownBy(() -> AccountMergeEmailConfigValidator.validated(environment))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage(FROM_NEEDED);
    environment
        .withProperty("app.email.from", "not an address")
        .withProperty("app.email.region", "nowhere");
    assertThatThrownBy(() -> AccountMergeEmailConfigValidator.validated(environment))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage(FROM_NEEDED);
    environment.withProperty("app.email.from", " Look Ahead <no-reply@dev.devlookahead.com> ");
    assertThatThrownBy(() -> AccountMergeEmailConfigValidator.validated(environment))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage(REGION_NEEDED);
    environment.withProperty("app.email.region", " us-east-2 ");
    var values = AccountMergeEmailConfigValidator.validated(environment);
    assertThat(values.enabled()).isTrue();
    assertThat(values.from()).isEqualTo("Look Ahead <no-reply@dev.devlookahead.com>");
    assertThat(values.region()).isEqualTo("us-east-2");
  }

  /**
   * The origin of the link: unset is decided by the environment and by whether the email is sent; a
   * set value must be an https origin.
   */
  @Test
  void thePublicUrlRefusalsComeInOneFixedOrder() {
    // Unset: DEV and Local have a default, whether or not the email is sent.
    for (String mode : new String[] {"dev", "local"})
      for (String enabled : new String[] {"false", "true"})
        assertThat(
                AccountMergeEmailConfigValidator.publicUrl(
                    new MockEnvironment()
                        .withProperty("app.deployment-environment", mode)
                        .withProperty("app.email.enabled", enabled)))
            .isEqualTo(AccountMergeEmailConfigValidator.DEV_PUBLIC_URL)
            .isEqualTo("https://dev.devlookahead.com");
    // Unset elsewhere: empty while the email is off, refused once it is sent.
    assertThat(
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment().withProperty("app.deployment-environment", "prod")))
        .isEmpty();
    assertThatThrownBy(() ->
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment()
                    .withProperty("app.deployment-environment", "prod")
                    .withProperty("app.email.enabled", "true")))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage(
            "LOOKAHEAD_EMAIL_ENABLED=true needs LOOKAHEAD_PUBLIC_URL (app.public-url) outside dev");
    // Set: the format is judged in every environment, also while the email is off.
    for (String mode : new String[] {"dev", "local", "prod"})
      for (String wrong :
          new String[] {
            "http://dev.devlookahead.com",
            "https://dev.devlookahead.com/path",
            "dev.devlookahead.com",
            "https://user@dev.devlookahead.com"
          })
        assertThatThrownBy(() ->
                AccountMergeEmailConfigValidator.publicUrl(
                    new MockEnvironment()
                        .withProperty("app.deployment-environment", mode)
                        .withProperty("app.public-url", wrong)))
            .as(mode + " " + wrong)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("LOOKAHEAD_PUBLIC_URL (app.public-url) must be an https origin");
    assertThat(
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment()
                    .withProperty("app.deployment-environment", "prod")
                    .withProperty("app.public-url", " https://lookahead.example:8443/ ")))
        .isEqualTo("https://lookahead.example:8443/");
  }
}
