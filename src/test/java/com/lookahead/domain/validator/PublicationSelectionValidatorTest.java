package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.log.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class PublicationSelectionValidatorTest {
  @Test
  void localNeedsNoAwsConfigurationAndDisablesCaching() {
    var selection =
        PublicationSelectionValidator.validated(
            new MockEnvironment().withProperty("app.deployment-environment", "local"));
    assertThat(selection.cloud()).isFalse();
    assertThat(selection.cacheBytes()).isZero();
  }

  @Test
  void cloudDoesNotFallBackToMountedContent() {
    for (String mode : new String[] {"dev", "prod", "", "test"}) {
      var env =
          new MockEnvironment()
              .withProperty("app.deployment-environment", mode)
              .withProperty("app.content.root", "/tmp/local")
              .withProperty("app.content.publication-path", "/tmp/local/publication.json");
      assertThatThrownBy(() -> PublicationSelectionValidator.validated(env))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void liveSelectionPinsNothingAndPartialPinsAreRejected(String mode) {
    var env =
        new MockEnvironment()
            .withProperty("app.deployment-environment", mode)
            .withProperty("app.publication.region", "us-east-2")
            .withProperty("app.publication.account-id", "123456789012")
            .withProperty("app.publication.bucket", "synthetic-publication")
            .withProperty("app.publication.directory", "/tmp/lookahead-publications")
            .withProperty("app.publication.release-id", "")
            .withProperty("app.publication.manifest-sha256", " ")
            .withProperty("app.publication.archive-sha256", "");
    var live = PublicationSelectionValidator.validated(env);
    assertThat(live.cloud()).isTrue();
    assertThat(live.live()).isTrue();
    assertThat(live.releaseId()).isNull();
    assertThat(live.manifestSha256()).isNull();
    assertThat(live.archiveSha256()).isNull();
    env.withProperty("app.publication.release-id", "v2026.10.27.01.00");
    assertThatThrownBy(() -> PublicationSelectionValidator.validated(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("live release");
    env.withProperty("app.publication.manifest-sha256", "b".repeat(64));
    assertThatThrownBy(() -> PublicationSelectionValidator.validated(env))
        .isInstanceOf(IllegalStateException.class);
    env.withProperty("app.publication.archive-sha256", "c".repeat(64));
    var pinned = PublicationSelectionValidator.validated(env);
    assertThat(pinned.live()).isFalse();
    assertThat(pinned.releaseId()).isEqualTo("v2026.10.27.01.00");
    env.withProperty("app.publication.release-id", "release-short");
    assertThatThrownBy(() -> PublicationSelectionValidator.validated(env))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            PublicationSelectionValidator.validated(
                    new MockEnvironment().withProperty("app.deployment-environment", "local"))
                .live())
        .isFalse();
  }

  static MockEnvironment pinnedEnvironment(String release) {
    return new MockEnvironment()
        .withProperty("app.deployment-environment", "dev")
        .withProperty("app.publication.region", "us-east-2")
        .withProperty("app.publication.account-id", "123456789012")
        .withProperty("app.publication.bucket", "synthetic-publication")
        .withProperty("app.publication.directory", "/tmp/lookahead-publications")
        .withProperty("app.publication.release-id", release)
        .withProperty("app.publication.manifest-sha256", "b".repeat(64))
        .withProperty("app.publication.archive-sha256", "c".repeat(64));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "v2026.10.27.01.00",
        "v2026.10.27.23.99",
        "v2026.10.27.01.100",
        "v2027.01.01.00.1234"
      })
  void pinAcceptsOnlyCalendarReleaseTags(String release) {
    assertThat(PublicationSelectionValidator.validated(pinnedEnvironment(release)).releaseId())
        .isEqualTo(release);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "release-aaaaaaaaaaaaaaaaaaaaaaaa",
        "2026.10.27.01.00",
        "V2026.10.27.01.00",
        "v2026.10.27.01.0",
        "v2026.10.27.01.012",
        "v2026.10.27.01.00 ",
        "v2026.10.27.01",
        "v2026-10-27-01-00",
        "v2026.10.27.01.00/",
        "v2026.10.27.01.00\n",
        "vX026.10.27.01.00"
      })
  void pinRejectsContentHashesAndMalformedTags(String release) {
    assertThatThrownBy(() -> PublicationSelectionValidator.validated(pinnedEnvironment(release)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid cloud publication configuration");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void validatesFullCloudConfigurationAndRejectsMixedProfilesAndUnboundedCache(String mode) {
    var env =
        new MockEnvironment()
            .withProperty("app.deployment-environment", mode)
            .withProperty("app.publication.region", "us-east-2")
            .withProperty("app.publication.account-id", "123456789012")
            .withProperty("app.publication.bucket", "synthetic-publication")
            .withProperty("app.publication.release-id", "v2026.10.27.01.00")
            .withProperty("app.publication.manifest-sha256", "b".repeat(64))
            .withProperty("app.publication.archive-sha256", "c".repeat(64))
            .withProperty("app.publication.directory", "/tmp/lookahead-publications");
    assertThat(PublicationSelectionValidator.validated(env).cloud()).isTrue();
    env.withProperty("app.publication.cache-bytes", "1000000000");
    assertThatThrownBy(() -> PublicationSelectionValidator.validated(env))
        .isInstanceOf(IllegalStateException.class);
    env.withProperty("app.publication.cache-bytes", "0");
    env.setActiveProfiles("local");
    assertThatThrownBy(() -> PublicationSelectionValidator.validated(env))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * DLV-1016: the checks moved from PublicationSelection.from in the same order: environment and
   * profiles; region, account and bucket must be set; the pin must be whole; the folder must be
   * set; then the formats of region, account, bucket, the three pin values, the folder and the two
   * cache bounds. Each case makes one more leading setting right and leaves everything after it
   * wrong.
   */
  @Test
  void whenSeveralSettingsAreWrongTheRefusalsComeInOneFixedOrder() {
    String invalid = "Invalid cloud publication configuration";
    // region, account-id, bucket, release-id, manifest-sha256, archive-sha256, directory,
    // cache-bytes, cache-entries -> reason, setting, exception text
    String[][] steps = {
      {
        "",
        "",
        "",
        "x",
        "",
        "",
        "",
        "-1",
        "0",
        "SETTING_MISSING",
        "app.publication.region (AWS_REGION)",
        "Missing cloud publication region"
      },
      {
        "nowhere",
        "",
        "",
        "x",
        "",
        "",
        "",
        "-1",
        "0",
        "SETTING_MISSING",
        "app.publication.account-id (LOOKAHEAD_AWS_ACCOUNT_ID)",
        "Missing cloud publication account-id"
      },
      {
        "nowhere",
        "1",
        "",
        "x",
        "",
        "",
        "",
        "-1",
        "0",
        "SETTING_MISSING",
        "app.publication.bucket (LOOKAHEAD_PUBLICATION_BUCKET)",
        "Missing cloud publication bucket"
      },
      {
        "nowhere",
        "1",
        "B",
        "x",
        "",
        "",
        "",
        "-1",
        "0",
        "PUBLICATION_PIN_INCOMPLETE",
        "LOOKAHEAD_PUBLICATION_RELEASE_ID, LOOKAHEAD_PUBLICATION_MANIFEST_SHA256 and"
            + " LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256",
        "Pin a release with its calendar tag and both digests, or leave all three unset to serve"
            + " the live release"
      },
      {
        "nowhere",
        "1",
        "B",
        "x",
        "y",
        "",
        "",
        "-1",
        "0",
        "PUBLICATION_PIN_INCOMPLETE",
        "LOOKAHEAD_PUBLICATION_RELEASE_ID, LOOKAHEAD_PUBLICATION_MANIFEST_SHA256 and"
            + " LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256",
        "Pin a release with its calendar tag and both digests, or leave all three unset to serve"
            + " the live release"
      },
      {
        "nowhere",
        "1",
        "B",
        "x",
        "y",
        "z",
        "",
        "-1",
        "0",
        "SETTING_MISSING",
        "app.publication.directory (LOOKAHEAD_PUBLICATION_DIRECTORY)",
        "Missing cloud publication directory"
      },
      {
        "nowhere",
        "1",
        "B",
        "x",
        "y",
        "z",
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.region (AWS_REGION)",
        invalid
      },
      {
        "us-east-2",
        "1",
        "B",
        "x",
        "y",
        "z",
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.account-id (LOOKAHEAD_AWS_ACCOUNT_ID)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "B",
        "x",
        "y",
        "z",
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.bucket (LOOKAHEAD_PUBLICATION_BUCKET)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "synthetic-publication",
        "x",
        "y",
        "z",
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.release-id (LOOKAHEAD_PUBLICATION_RELEASE_ID)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "synthetic-publication",
        "v2026.10.27.01.00",
        "y",
        "z",
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.manifest-sha256 (LOOKAHEAD_PUBLICATION_MANIFEST_SHA256)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "synthetic-publication",
        "v2026.10.27.01.00",
        "b".repeat(64),
        "z",
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.archive-sha256 (LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "synthetic-publication",
        "v2026.10.27.01.00",
        "b".repeat(64),
        "c".repeat(64),
        "relative",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.directory (LOOKAHEAD_PUBLICATION_DIRECTORY)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "synthetic-publication",
        "v2026.10.27.01.00",
        "b".repeat(64),
        "c".repeat(64),
        "/tmp/lookahead-publications",
        "-1",
        "0",
        "SETTING_INVALID",
        "app.publication.cache-bytes (LOOKAHEAD_CONTENT_CACHE_BYTES)",
        invalid
      },
      {
        "us-east-2",
        "123456789012",
        "synthetic-publication",
        "v2026.10.27.01.00",
        "b".repeat(64),
        "c".repeat(64),
        "/tmp/lookahead-publications",
        "0",
        "0",
        "SETTING_INVALID",
        "app.publication.cache-entries (LOOKAHEAD_CONTENT_CACHE_ENTRIES)",
        invalid
      }
    };
    String[] fields = {
      "region",
      "account-id",
      "bucket",
      "release-id",
      "manifest-sha256",
      "archive-sha256",
      "directory",
      "cache-bytes",
      "cache-entries"
    };
    // The environment comes before everything else.
    for (String mode : new String[] {"test", ""}) {
      try (var log = LogCapture.start("lookahead.startup")) {
        assertThatThrownBy(() ->
                PublicationSelectionValidator.validated(
                    new MockEnvironment().withProperty("app.deployment-environment", mode)))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Explicit consistent publication environment required");
        assertThat(LogCapture.keys(log.only("startup.check")))
            .containsEntry("reason", "ENVIRONMENT_INCONSISTENT")
            .containsEntry("setting", "app.deployment-environment (LOOKAHEAD_ENVIRONMENT)");
      }
    }
    for (String[] step : steps) {
      var environment = new MockEnvironment().withProperty("app.deployment-environment", "dev");
      for (int i = 0; i < fields.length; i++)
        environment.withProperty("app.publication." + fields[i], step[i]);
      try (var log = LogCapture.start("lookahead.startup")) {
        assertThatThrownBy(() -> PublicationSelectionValidator.validated(environment))
            .as(step[10])
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(step[11]);
        var line = log.only("startup.check");
        assertThat(LogCapture.keys(line))
            .containsEntry("reason", step[9])
            .containsEntry("setting", step[10]);
        assertThat(line.getFormattedMessage()).startsWith("Startup refused: " + step[10] + " ");
      }
    }
  }
}
