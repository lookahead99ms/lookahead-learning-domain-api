package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.PublicationSelection;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DLV-1016: the runtime store checks, moved from PlatformReviewStoreConfigValues.from: the same
 * refusals in the same order.
 */
class PlatformReviewStoreConfigValidatorTest {
  static final PublicationSelection LOCAL =
      new PublicationSelection(false, null, null, null, null, null, null, null, 0, 0);
  static final PublicationSelection CLOUD =
      new PublicationSelection(
          true,
          "us-east-2",
          "123456789012",
          "synthetic-publication",
          null,
          null,
          null,
          Path.of("/tmp/lookahead-publications"),
          0,
          1);

  @Test
  void theSettingsArePassedOnUnchangedWhenTheyAgree() {
    var local =
        PlatformReviewStoreConfigValidator.validated(
            "/var/reviews", "", "", "reviews/", "support-requests/", "delivery/", LOCAL);
    assertThat(local.directory()).isEqualTo("/var/reviews");
    assertThat(local.bucket()).isEmpty();
    var cloud =
        PlatformReviewStoreConfigValidator.validated(
            "",
            "synthetic-assets",
            "alias/synthetic",
            "reviews/",
            "support-requests/",
            "delivery/",
            CLOUD);
    assertThat(cloud)
        .extracting("bucket", "kmsKey", "reviews", "requests", "delivery")
        .containsExactly(
            "synthetic-assets", "alias/synthetic", "reviews/", "support-requests/", "delivery/");
    // Nothing configured is not refused: every store then answers 503.
    assertThat(PlatformReviewStoreConfigValidator.validated("", "", "", "", "", "", CLOUD).bucket())
        .isEmpty();
  }

  /** Each case makes one more leading setting right and leaves everything after it wrong. */
  @Test
  void whenSeveralSettingsAreWrongTheRefusalsComeInOneFixedOrder() {
    // A directory in cloud mode comes first, whatever else is wrong.
    assertThatThrownBy(() ->
            PlatformReviewStoreConfigValidator.validated(
                "/var/reviews", "synthetic-assets", "", "", "", "", CLOUD))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Cloud review storage must use S3");
    // Then S3 settings outside cloud mode, or beside a directory.
    for (String directory : new String[] {"", "/var/reviews"})
      for (String[] s3 : new String[][] {{"synthetic-assets", ""}, {"", "alias/synthetic"}})
        assertThatThrownBy(() ->
                PlatformReviewStoreConfigValidator.validated(
                    directory, s3[0], s3[1], "", "", "", LOCAL))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("S3 reviews require explicit cloud configuration");
    // Then the three prefixes, in the order reviews, support requests, delivery.
    String[][] steps = {
      {"", "", "", "app.reviews.prefix (LOOKAHEAD_REVIEWS_PREFIX)"},
      {"reviews/", "", "", "app.support-requests.prefix (LOOKAHEAD_SUPPORT_REQUESTS_PREFIX)"},
      {"reviews/", "support-requests/", " ", "app.delivery.prefix (LOOKAHEAD_DELIVERY_PREFIX)"}
    };
    for (String[] step : steps) {
      try (var log = LogCapture.start("lookahead.startup")) {
        assertThatThrownBy(() ->
                PlatformReviewStoreConfigValidator.validated(
                    "", "synthetic-assets", "", step[0], step[1], step[2], CLOUD))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Runtime store prefixes are required with S3 review storage");
        var line = log.only("startup.check");
        assertThat(LogCapture.keys(line))
            .containsEntry("reason", "SETTING_MISSING")
            .containsEntry("setting", step[3]);
        assertThat(line.getFormattedMessage())
            .isEqualTo(
                "Startup refused: "
                    + step[3]
                    + " is not set; every runtime store needs its prefix in the assets bucket; the"
                    + " app stops before it takes traffic");
      }
    }
  }
}
