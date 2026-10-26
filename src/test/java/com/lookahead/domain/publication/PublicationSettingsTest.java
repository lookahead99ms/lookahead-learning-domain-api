package com.lookahead.domain.publication;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class PublicationSettingsTest {
    @Test void localNeedsNoAwsConfigurationAndDisablesCaching() {
        var settings = PublicationSettings.from(new MockEnvironment().withProperty("app.deployment-environment","local"));
        assertThat(settings.cloud()).isFalse();assertThat(settings.cacheBytes()).isZero();
    }
    @Test void cloudDoesNotFallBackToMountedContent() {
        for (String mode : new String[]{"dev","prod","","test"}) {
            var env = new MockEnvironment().withProperty("app.deployment-environment",mode)
                    .withProperty("app.content.root","/tmp/local").withProperty("app.content.publication-path","/tmp/local/publication.json");
            assertThatThrownBy(() -> PublicationSettings.from(env)).isInstanceOf(IllegalStateException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void liveSelectionPinsNothingAndPartialPinsAreRejected(String mode) {
        var env = new MockEnvironment().withProperty("app.deployment-environment",mode)
                .withProperty("app.publication.region","us-east-2").withProperty("app.publication.account-id","123456789012")
                .withProperty("app.publication.bucket","synthetic-publication").withProperty("app.publication.directory","/tmp/lookahead-publications")
                .withProperty("app.publication.release-id","").withProperty("app.publication.manifest-sha256"," ").withProperty("app.publication.archive-sha256","");
        var live = PublicationSettings.from(env);
        assertThat(live.cloud()).isTrue(); assertThat(live.live()).isTrue();
        assertThat(live.releaseId()).isNull(); assertThat(live.manifestSha256()).isNull(); assertThat(live.archiveSha256()).isNull();
        env.withProperty("app.publication.release-id","release-"+"a".repeat(24));
        assertThatThrownBy(()->PublicationSettings.from(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("live release");
        env.withProperty("app.publication.manifest-sha256","b".repeat(64));
        assertThatThrownBy(()->PublicationSettings.from(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("app.publication.archive-sha256","c".repeat(64));
        var pinned = PublicationSettings.from(env);
        assertThat(pinned.live()).isFalse(); assertThat(pinned.releaseId()).isEqualTo("release-"+"a".repeat(24));
        env.withProperty("app.publication.release-id","release-short");
        assertThatThrownBy(()->PublicationSettings.from(env)).isInstanceOf(IllegalStateException.class);
        assertThat(PublicationSettings.from(new MockEnvironment().withProperty("app.deployment-environment","local")).live()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void validatesFullCloudConfigurationAndRejectsMixedProfilesAndUnboundedCache(String mode) {
        var env = new MockEnvironment().withProperty("app.deployment-environment",mode)
                .withProperty("app.publication.region","us-east-2").withProperty("app.publication.account-id","123456789012")
                .withProperty("app.publication.bucket","synthetic-publication").withProperty("app.publication.release-id","release-"+"a".repeat(24))
                .withProperty("app.publication.manifest-sha256","b".repeat(64)).withProperty("app.publication.archive-sha256","c".repeat(64))
                .withProperty("app.publication.directory","/tmp/lookahead-publications");
        assertThat(PublicationSettings.from(env).cloud()).isTrue();
        env.withProperty("app.publication.cache-bytes","1000000000");
        assertThatThrownBy(()->PublicationSettings.from(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("app.publication.cache-bytes","0");env.setActiveProfiles("local");
        assertThatThrownBy(()->PublicationSettings.from(env)).isInstanceOf(IllegalStateException.class);
    }
}
