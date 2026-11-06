package com.lookahead.domain.review.platform;

import com.lookahead.domain.publication.PublicationSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-971: one object store per runtime root, Local sub-folders or S3 prefixes; cloud refuses a blank prefix. */
class PlatformReviewConfigurationTest {
    @TempDir Path directory;
    final PlatformReviewConfiguration configuration = new PlatformReviewConfiguration();
    final JsonMapper mapper = JsonMapper.builder().build();
    @SuppressWarnings("unchecked") final ObjectProvider<S3Client> clients = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final PublicationSettings local = PublicationSettings.from(new MockEnvironment().withProperty("app.deployment-environment", "local"));
    final PublicationSettings cloud = new PublicationSettings(true, "us-east-2", "123456789012", "synthetic-assets", null, null, null, Path.of("/tmp/lookahead-publications"), 1024, 4);
    static final String KEY = "arn:aws:kms:us-east-2:123456789012:key/00000000-0000-0000-0000-000000000000";

    @Test void localStoresAreSubFoldersOfTheReviewDirectoryAndTheDirectoryItselfIsTheLegacyStore() throws Exception {
        var settings = configuration.reviewStoreSettings(directory.toString(), "", "", "reviews/", "support-requests/", "delivery/", "", local);
        var stores = configuration.reviewStores(settings, local, clients, mapper, new ReviewRecordNumbers.InMemory());
        stores.reviews().objects().put("records/x.json", "{}".getBytes());
        stores.requests().objects().put("records/y.json", "{}".getBytes());
        configuration.deliveryPlanStore(settings, local, clients, mapper);
        assertThat(Files.exists(directory.resolve("reviews/records/x.json"))).isTrue();
        assertThat(Files.exists(directory.resolve("support-requests/records/y.json"))).isTrue();
        var legacy = PlatformReviewConfiguration.objects(settings, local, clients, null);
        legacy.put("reviews/2026-10-12.json", "{}".getBytes());
        assertThat(Files.exists(directory.resolve("reviews/2026-10-12.json"))).isTrue();
        var importer = configuration.legacyReviewImport(settings, local, clients, stores, mapper);
        assertThat(importer).isNotNull();
        var access = mock(com.lookahead.domain.access.StaffAccessService.class);
        var plans = configuration.deliveryPlanService(configuration.deliveryPlanStore(settings, local, clients, mapper), access, new ReviewStatusHistory.InMemory(), local);
        assertThat(plans.environment()).isEqualTo("local");
        assertThat(configuration.deliveryPlanService(configuration.deliveryPlanStore(settings, local, clients, mapper), access, new ReviewStatusHistory.InMemory(), cloud).environment()).isEqualTo("dev");
        var jdbc = mock(JdbcTemplate.class);
        assertThat(configuration.reviewRecordNumbers(jdbc)).isInstanceOf(ReviewRecordNumbers.Jdbc.class);
        assertThat(configuration.reviewStatusHistory(jdbc)).isInstanceOf(ReviewStatusHistory.Jdbc.class);
        verifyNoInteractions(clients);
    }

    @Test void cloudStoresAreS3PrefixesWithTheSharedBucketAndKeyAndABlankPrefixRefusesStartup() {
        when(clients.getObject()).thenReturn(mock(S3Client.class));
        var settings = configuration.reviewStoreSettings("", "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/", "platform-reviews/", cloud);
        for (String root : List.of("reviews/", "support-requests/", "delivery/"))
            assertThat(PlatformReviewConfiguration.objects(settings, cloud, clients, root)).isInstanceOf(S3PlatformReviewObjects.class);
        assertThat(PlatformReviewConfiguration.objects(settings, cloud, clients, null)).isInstanceOf(S3PlatformReviewObjects.class);
        var noLegacy = configuration.reviewStoreSettings("", "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/", "", cloud);
        assertThat(PlatformReviewConfiguration.objects(noLegacy, cloud, clients, null)).as("no legacy prefix: no import source").isNull();
        assertThatThrownBy(() -> configuration.reviewStoreSettings("", "synthetic-assets", KEY, "", "support-requests/", "delivery/", "", cloud))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Runtime store prefixes are required");
        assertThatThrownBy(() -> configuration.reviewStoreSettings(directory.toString(), "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/", "", cloud))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cloud review storage must use S3");
        assertThatThrownBy(() -> configuration.reviewStoreSettings("", "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/", "", local))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("S3 reviews require explicit cloud configuration");
        // Nothing configured: every store answers 503 through the missing implementation, the legacy store is absent.
        var none = configuration.reviewStoreSettings("", "", "", "reviews/", "support-requests/", "delivery/", "", local);
        var missing = PlatformReviewConfiguration.objects(none, local, clients, "reviews/");
        for (var call : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(() -> missing.read("x"), () -> missing.write("x", new byte[0], null), () -> missing.put("x", new byte[0]),
                () -> missing.list("x"), () -> missing.listAll("x"), () -> missing.delete("x")))
            assertThatThrownBy(call).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(PlatformReviewConfiguration.objects(none, local, clients, null)).isNull();
        assertThat(Set.of(none.reviews(), none.requests(), none.delivery())).containsExactlyInAnyOrder("reviews/", "support-requests/", "delivery/");
    }
}
