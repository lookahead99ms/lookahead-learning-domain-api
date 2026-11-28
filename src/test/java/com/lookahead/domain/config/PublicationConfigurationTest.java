package com.lookahead.domain.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.client.PublicationObjectClient;
import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.service.content.PublicationArchiveLoadServiceTest;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PublicationConfigurationTest {
  @TempDir Path directory;

  ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withUserConfiguration(PublicationConfiguration.class)
        .withPropertyValues("spring.profiles.active=accounts")
        .withBean(ObjectMapper.class, JsonMapper::new)
        .withBean(PublicationFileStore.class, PublicationFileStore::new)
        .withBean(
            ProtectedContentManifestService.class,
            () ->
                new ProtectedContentManifestService(
                    new JsonMapper(), "", "", new PublicationFileStore()))
        .withBean(StudyPlanSnapshotValidator.class, () -> mock(StudyPlanSnapshotValidator.class));
  }

  @Test
  void localContextKeepsMountsAndCreatesNoAwsClientOrCredentialProvider() {
    context()
        .withPropertyValues(
            "app.deployment-environment=local",
            "server.address=0.0.0.0",
            "app.content.root=/synthetic/local/content",
            "app.content.publication-path=/synthetic/local/publication.json",
            "app.accounts.catalog-path=/synthetic/local/catalog.json")
        .run(context -> {
          assertThat(context)
              .hasNotFailed()
              .doesNotHaveBean(S3Client.class)
              .doesNotHaveBean(ContainerCredentialsProvider.class);
          assertThat(context.getBean(PublicationLocation.class).contentRoot())
              .isEqualTo("/synthetic/local/content");
          assertThat(context.getBean(PublicationLocation.class).cloud()).isFalse();
          assertThat(context).hasBean("contentPublication");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void cloudContextWithMissingSecretsCannotBecomeReadyOrUseLocalMounts(String mode) {
    context()
        .withPropertyValues(
            "app.deployment-environment=" + mode, "app.content.root=/synthetic/local/content")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void s3SourceListsOnlyTheRequestedPrefixWithOwnerCheckAndBoundedPagination() throws Exception {
    var client = mock(S3Client.class);
    var selection =
        new PublicationSelection(
            true,
            "us-east-2",
            "123456789012",
            "synthetic-publication",
            null,
            null,
            null,
            Path.of("/tmp/lookahead-publications"),
            1024,
            4);
    var requests = new ArrayList<ListObjectsV2Request>();
    var t0 = Instant.parse("2026-10-26T10:00:00Z");
    when(client.listObjectsV2(any(Consumer.class)))
        .thenAnswer(invocation -> {
          Consumer<ListObjectsV2Request.Builder> consumer = invocation.getArgument(0);
          var builder = ListObjectsV2Request.builder();
          consumer.accept(builder);
          var request = builder.build();
          requests.add(request);
          boolean first = request.continuationToken() == null;
          return ListObjectsV2Response.builder()
              .contents(
                  S3Object.builder().key(first ? "live/a" : "live/b").lastModified(t0).build())
              .isTruncated(first)
              .nextContinuationToken(first ? "next" : null)
              .build();
        });
    when(client.getObject(any(Consumer.class)))
        .thenAnswer(invocation -> {
          Consumer<GetObjectRequest.Builder> consumer = invocation.getArgument(0);
          var builder = GetObjectRequest.builder();
          consumer.accept(builder);
          var request = builder.build();
          assertThat(request.bucket()).isEqualTo("synthetic-publication");
          assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
          return new ResponseInputStream<>(
              GetObjectResponse.builder().build(),
              AbortableInputStream.create(new ByteArrayInputStream(request.key().getBytes())));
        });
    var source = new PublicationConfiguration.Cloud().publicationObjects(client, selection);
    var listed = source.list("live/");
    assertThat(listed)
        .extracting(PublicationObjectClient.ListedObject::key)
        .containsExactly("live/a", "live/b");
    assertThat(listed.get(0).lastModified()).isEqualTo(t0);
    assertThat(requests)
        .hasSize(2)
        .allSatisfy(request -> {
          assertThat(request.bucket()).isEqualTo("synthetic-publication");
          assertThat(request.prefix()).isEqualTo("live/");
          assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
          assertThat(request.maxKeys()).isEqualTo(PublicationObjectClient.MAX_LIVE_OBJECTS + 1);
        });
    assertThat(requests.get(1).continuationToken()).isEqualTo("next");
    assertThat(new String(source.open("live/a/manifest.json").readAllBytes()))
        .isEqualTo("live/a/manifest.json");
    // An endless listing is cut off instead of being read into memory.
    when(client.listObjectsV2(any(Consumer.class)))
        .thenAnswer(invocation ->
            ListObjectsV2Response.builder()
                .contents(
                    IntStream.range(0, 40)
                        .mapToObj(
                            i -> S3Object.builder().key("live/x" + i).lastModified(t0).build())
                        .toList())
                .isTruncated(true)
                .nextContinuationToken("more")
                .build());
    assertThatThrownBy(() -> source.list("live/")).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void cloudBeansInitializeVerifiedPublicationAndReadinessWithControlledObjectSource(String mode)
      throws Exception {
    var helper = new PublicationArchiveLoadServiceTest();
    helper.directory = directory;
    var fixture = helper.fixture(null, false, false);
    // Cloud production directory is Linux /tmp; fixture constructor uses the real test path.
    var selected = helper.settings(fixture);
    new ApplicationContextRunner()
        .withUserConfiguration(PublicationConfiguration.Cloud.class)
        .withBean(PublicationSelection.class, () -> selected)
        .withBean(
            "syntheticObjects",
            PublicationObjectClient.class,
            () -> helper.source(fixture),
            definition -> definition.setPrimary(true))
        .withPropertyValues(
            "app.deployment-environment=" + mode,
            "app.publication.region=us-east-2",
            "server.address=127.0.0.1")
        .run(context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(S3Client.class)
              .hasSingleBean(ContainerCredentialsProvider.class);
          var configuration = new PublicationConfiguration();
          var loaded =
              configuration.publicationLoaded(
                  configuration.publicationLoadService(
                      new JsonMapper(), new PublicationFileStore()),
                  selected,
                  configuration.publicationSourceConfigValues(context.getEnvironment()),
                  context.getBeanProvider(PublicationObjectClient.class));
          var location = configuration.publicationLocation(loaded);
          assertThat(configuration.publicationLiveRelease(loaded).configured()).isFalse();
          var policy =
              new ProtectedContentManifestService(
                  new JsonMapper(), location, new PublicationFileStore());
          var catalog =
              new StudyPlanSnapshotValidator(
                  new AccountCatalogService(new JsonMapper(), location, new PublicationFileStore())
                      .catalog());
          assertThat(
                  configuration
                      .contentPublication(
                          location, policy, catalog, loaded.release(), new PublicationFileStore())
                      .health()
                      .getStatus()
                      .getCode())
              .isEqualTo("UP");
        });
    helper.writableForFixtureCleanup();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "blank", "repeated", "advancing-empty"})
  void incompleteOrNonProgressingListingsFailClosedWithinBoundedRequests(String mode) {
    var client = mock(S3Client.class);
    var selection =
        new PublicationSelection(
            true,
            "us-east-2",
            "123456789012",
            "synthetic-publication",
            null,
            null,
            null,
            Path.of("/tmp/lookahead-publications"),
            1024,
            4);
    var calls = new AtomicInteger();
    when(client.listObjectsV2(any(Consumer.class)))
        .thenAnswer(invocation -> {
          int call = calls.incrementAndGet();
          if (call > PublicationObjectClient.MAX_LIVE_OBJECTS + 1)
            throw new AssertionError("Publication pagination exceeded its request bound");
          var page = ListObjectsV2Response.builder().isTruncated(true);
          if (mode.equals("blank")) page.nextContinuationToken(" ");
          if (mode.equals("repeated")) page.nextContinuationToken("same-token");
          if (mode.equals("advancing-empty")) page.nextContinuationToken("token-" + call);
          return page.build();
        });
    var source = new PublicationConfiguration.Cloud().publicationObjects(client, selection);
    assertThatThrownBy(() -> source.list("live/"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Cloud publication listing is incomplete or exceeds its request bound");
    assertThat(calls.get()).isLessThanOrEqualTo(PublicationObjectClient.MAX_LIVE_OBJECTS + 1);
    if (mode.equals("missing") || mode.equals("blank")) assertThat(calls.get()).isEqualTo(1);
    if (mode.equals("repeated")) assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void emptyPagesWithAdvancingTokensCanReachACompleteListing() throws Exception {
    var client = mock(S3Client.class);
    var selection =
        new PublicationSelection(
            true,
            "us-east-2",
            "123456789012",
            "synthetic-publication",
            null,
            null,
            null,
            Path.of("/tmp/lookahead-publications"),
            1024,
            4);
    // Counted thenAnswer like the sibling tests: thenReturn and verify both call MockUtil.isMock,
    // whose mock-maker
    // assertion (Mockito 5.23, surefire runs with -ea) fails for this S3Client mock once another
    // mock maker is registered.
    var calls = new AtomicInteger();
    when(client.listObjectsV2(any(Consumer.class)))
        .thenAnswer(invocation ->
            calls.incrementAndGet() == 1
                ? ListObjectsV2Response.builder()
                    .isTruncated(true)
                    .nextContinuationToken("next-page")
                    .build()
                : ListObjectsV2Response.builder()
                    .isTruncated(false)
                    .contents(
                        S3Object.builder()
                            .key("live/v2026.10.30.00.00/manifest.json")
                            .lastModified(Instant.EPOCH)
                            .build())
                    .build());
    var source = new PublicationConfiguration.Cloud().publicationObjects(client, selection);
    assertThat(source.list("live/"))
        .extracting(PublicationObjectClient.ListedObject::key)
        .containsExactly("live/v2026.10.30.00.00/manifest.json");
    assertThat(calls.get()).isEqualTo(2);
  }
}
