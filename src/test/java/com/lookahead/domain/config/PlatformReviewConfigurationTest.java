package com.lookahead.domain.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.client.PlatformReviewS3Client;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.review.PlatformReviewFileSystemObjectStore;
import com.lookahead.domain.db.store.review.PlatformReviewMissingObjectStore;
import com.lookahead.domain.db.store.review.PlatformReviewObjects;
import com.lookahead.domain.db.store.review.PlatformReviewRecordNumberMemoryStore;
import com.lookahead.domain.db.store.review.PlatformReviewRecordNumberStore;
import com.lookahead.domain.db.store.review.PlatformReviewS3ObjectStore;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistoryMemoryStore;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistoryStore;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.validator.DeliveryPlanItemValidator;
import com.lookahead.domain.validator.DeliveryPlanRequestValidator;
import com.lookahead.domain.validator.PublicationSelectionValidator;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-971: one object store per runtime root, Local sub-folders or S3 prefixes; cloud refuses a
 * blank prefix.
 */
class PlatformReviewConfigurationTest {
  @TempDir Path directory;
  final PlatformReviewConfiguration configuration = new PlatformReviewConfiguration();
  final DeliveryPlanConfiguration delivery = new DeliveryPlanConfiguration();
  final JsonMapper mapper = JsonMapper.builder().build();

  @SuppressWarnings("unchecked")
  final ObjectProvider<S3Client> clients =
      mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));

  final PublicationSelection local =
      PublicationSelectionValidator.validated(
          new MockEnvironment().withProperty("app.deployment-environment", "local"));
  final PublicationSelection cloud =
      new PublicationSelection(
          true,
          "us-east-2",
          "123456789012",
          "synthetic-assets",
          null,
          null,
          null,
          Path.of("/tmp/lookahead-publications"),
          1024,
          4);
  static final String KEY =
      "arn:aws:kms:us-east-2:123456789012:key/00000000-0000-0000-0000-000000000000";

  @Test
  void localStoresAreSubFoldersOfTheReviewDirectory() throws Exception {
    var values =
        configuration.reviewStoreConfigValues(
            directory.toString(), "", "", "reviews/", "support-requests/", "delivery/", local);
    var folders = new PlatformReviewConfiguration.Directory();
    var stores =
        configuration.reviewStores(
            folders.reviewObjects(values),
            folders.requestObjects(values),
            mapper,
            new PlatformReviewRecordNumberMemoryStore());
    stores.reviews().objects().put("records/x.json", "{}".getBytes());
    stores.requests().objects().put("records/y.json", "{}".getBytes());
    delivery.deliveryPlanStore(folders.deliveryObjects(values), mapper);
    assertThat(Files.exists(directory.resolve("reviews/records/x.json"))).isTrue();
    assertThat(Files.exists(directory.resolve("support-requests/records/y.json"))).isTrue();
    var access = mock(StaffAccessService.class);
    var plans =
        delivery.deliveryPlanService(
            delivery.deliveryPlanStore(folders.deliveryObjects(values), mapper),
            delivery.deliveryPlanFileValidator(mapper),
            access,
            new PlatformReviewStatusHistoryMemoryStore(),
            local,
            new DeliveryPlanItemValidator(),
            new MockEnvironment(),
            new DeliveryPlanRequestValidator());
    assertThat(plans.environment()).isEqualTo("local");
    assertThat(
            delivery
                .deliveryPlanService(
                    delivery.deliveryPlanStore(folders.deliveryObjects(values), mapper),
                    delivery.deliveryPlanFileValidator(mapper),
                    access,
                    new PlatformReviewStatusHistoryMemoryStore(),
                    cloud,
                    new DeliveryPlanItemValidator(),
                    new MockEnvironment(),
                    new DeliveryPlanRequestValidator())
                .environment())
        .isEqualTo("dev");
    var client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var tables = DynamoTableNames.local();
    assertThat(configuration.platformReviewRecordNumberStore(client, tables))
        .isInstanceOf(PlatformReviewRecordNumberStore.class);
    assertThat(configuration.platformReviewStatusHistoryStore(client, tables))
        .isInstanceOf(PlatformReviewStatusHistoryStore.class);
    verifyNoInteractions(client);
    verifyNoInteractions(clients);
  }

  @Test
  void cloudStoresAreS3PrefixesWithTheSharedBucketAndKeyAndABlankPrefixRefusesStartup() {
    when(clients.getObject())
        .thenReturn(mock(S3Client.class, withSettings().mockMaker(MockMakers.SUBCLASS)));
    var values =
        configuration.reviewStoreConfigValues(
            "", "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/", cloud);
    var prefixes = new PlatformReviewConfiguration.S3();
    var s3 = clients.getObject();
    // DLV-1016: one S3 client of the bucket, and on it one store per root.
    PlatformReviewS3Client client = prefixes.platformReviewS3Client(values, cloud, s3);
    for (var store :
        List.of(
            prefixes.reviewObjects(client, values),
            prefixes.requestObjects(client, values),
            prefixes.deliveryObjects(client, values)))
      assertThat(store).isInstanceOf(PlatformReviewS3ObjectStore.class);
    verifyNoInteractions(s3);
    assertThatThrownBy(() ->
            configuration.reviewStoreConfigValues(
                "", "synthetic-assets", KEY, "", "support-requests/", "delivery/", cloud))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Runtime store prefixes are required");
    assertThatThrownBy(() ->
            configuration.reviewStoreConfigValues(
                directory.toString(),
                "synthetic-assets",
                KEY,
                "reviews/",
                "support-requests/",
                "delivery/",
                cloud))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cloud review storage must use S3");
    assertThatThrownBy(() ->
            configuration.reviewStoreConfigValues(
                "", "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/", local))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S3 reviews require explicit cloud configuration");
    // Nothing configured: every store answers 503 through the missing implementation.
    var none =
        configuration.reviewStoreConfigValues(
            "", "", "", "reviews/", "support-requests/", "delivery/", local);
    var missing = new PlatformReviewConfiguration.Missing().reviewObjects(none);
    for (var call :
        List.<ThrowableAssert.ThrowingCallable>of(
            () -> missing.read("x"),
            () -> missing.write("x", new byte[0], null),
            () -> missing.put("x", new byte[0]),
            () -> missing.listAll("x"),
            () -> missing.delete("x")))
      assertThatThrownBy(call).isInstanceOf(UncheckedIOException.class);
    assertThat(Set.of(none.reviews(), none.requests(), none.delivery()))
        .containsExactlyInAnyOrder("reviews/", "support-requests/", "delivery/");
  }

  /**
   * DLV-1016: the condition on each nested configuration, asked of a context: one kind of store per
   * setting, three beans each.
   */
  @Test
  void theSettingsChooseOneKindOfObjectStore() {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(
                PlatformReviewConfiguration.S3.class,
                PlatformReviewConfiguration.Directory.class,
                PlatformReviewConfiguration.Missing.class)
            .withPropertyValues("spring.profiles.active=accounts")
            .withBean(PublicationSelection.class, () -> cloud)
            .withBean(S3Client.class, () -> mock(S3Client.class));
    runner
        .withBean(
            PlatformReviewStoreConfigValues.class,
            () ->
                new PlatformReviewStoreConfigValues(
                    "", "synthetic-assets", KEY, "reviews/", "support-requests/", "delivery/"))
        .withPropertyValues("app.platform-reviews.bucket=synthetic-assets")
        .run(context -> {
          assertThat(context.getBeansOfType(PlatformReviewObjects.class).keySet())
              .containsExactlyInAnyOrder("reviewObjects", "requestObjects", "deliveryObjects");
          assertThat(context.getBean("deliveryObjects"))
              .isInstanceOf(PlatformReviewS3ObjectStore.class);
          assertThat(context.getBeansOfType(PlatformReviewS3Client.class).keySet())
              .as("the one S3 client the three stores share")
              .containsExactly("platformReviewS3Client");
        });
    runner
        .withBean(
            PlatformReviewStoreConfigValues.class,
            () ->
                new PlatformReviewStoreConfigValues(
                    directory.toString(), "", "", "reviews/", "support-requests/", "delivery/"))
        .withPropertyValues("app.platform-reviews.directory=" + directory)
        .run(context -> {
          assertThat(context.getBeansOfType(PlatformReviewObjects.class)).hasSize(3);
          assertThat(context.getBean("reviewObjects"))
              .isInstanceOf(PlatformReviewFileSystemObjectStore.class);
          assertThat(context.getBeansOfType(PlatformReviewS3Client.class))
              .as("no S3 client without a bucket")
              .isEmpty();
        });
    runner
        .withBean(
            PlatformReviewStoreConfigValues.class,
            () ->
                new PlatformReviewStoreConfigValues(
                    "", "", "", "reviews/", "support-requests/", "delivery/"))
        .run(context -> {
          assertThat(context.getBeansOfType(PlatformReviewObjects.class)).hasSize(3);
          assertThat(context.getBean("requestObjects"))
              .isInstanceOf(PlatformReviewMissingObjectStore.class);
          assertThat(context.getBeansOfType(PlatformReviewS3Client.class)).isEmpty();
        });
  }
}
