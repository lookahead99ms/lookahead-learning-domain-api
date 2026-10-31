package com.lookahead.domain.publication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class PublicationConfigurationTest {
    @TempDir Path directory;
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(PublicationConfiguration.class)
                .withPropertyValues("spring.profiles.active=accounts")
                .withBean(ObjectMapper.class,JsonMapper::new)
                .withBean(ProtectedContentPolicy.class,()->new ProtectedContentPolicy(new JsonMapper(),"",""))
                .withBean(SnapshotValidator.class,()->mock(SnapshotValidator.class));
    }
    @Test void localContextKeepsMountsAndCreatesNoAwsClientOrCredentialProvider() {
        context().withPropertyValues("app.deployment-environment=local", "server.address=0.0.0.0", "app.content.root=/synthetic/local/content",
                "app.content.publication-path=/synthetic/local/publication.json", "app.accounts.catalog-path=/synthetic/local/catalog.json")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(S3Client.class).doesNotHaveBean(ContainerCredentialsProvider.class);
                    assertThat(context.getBean(PublicationLocation.class).contentRoot()).isEqualTo("/synthetic/local/content");
                    assertThat(context.getBean(PublicationLocation.class).cloud()).isFalse();
                    assertThat(context).hasBean("contentPublication");
                });
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void cloudContextWithMissingSecretsCannotBecomeReadyOrUseLocalMounts(String mode) {
        context().withPropertyValues("app.deployment-environment="+mode, "app.content.root=/synthetic/local/content")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void s3SourceListsOnlyTheRequestedPrefixWithOwnerCheckAndBoundedPagination() throws Exception {
        var client = mock(S3Client.class);
        var settings = new PublicationSettings(true,"us-east-2","123456789012","synthetic-publication",null,null,null,Path.of("/tmp/lookahead-publications"),1024,4);
        var requests = new java.util.ArrayList<software.amazon.awssdk.services.s3.model.ListObjectsV2Request>();
        var t0 = java.time.Instant.parse("2026-10-26T10:00:00Z");
        when(client.listObjectsV2(any(java.util.function.Consumer.class))).thenAnswer(invocation -> {
            java.util.function.Consumer<software.amazon.awssdk.services.s3.model.ListObjectsV2Request.Builder> consumer = invocation.getArgument(0);
            var builder = software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder(); consumer.accept(builder);
            var request = builder.build(); requests.add(request);
            boolean first = request.continuationToken() == null;
            return software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                .contents(software.amazon.awssdk.services.s3.model.S3Object.builder().key(first ? "live/a" : "live/b").lastModified(t0).build())
                .isTruncated(first).nextContinuationToken(first ? "next" : null).build();
        });
        when(client.getObject(any(java.util.function.Consumer.class))).thenAnswer(invocation -> {
            java.util.function.Consumer<software.amazon.awssdk.services.s3.model.GetObjectRequest.Builder> consumer = invocation.getArgument(0);
            var builder = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder(); consumer.accept(builder);
            var request = builder.build();
            assertThat(request.bucket()).isEqualTo("synthetic-publication"); assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
            return new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(request.key().getBytes())));
        });
        var source = new PublicationConfiguration.Cloud().publicationObjects(client, settings);
        var listed = source.list("live/");
        assertThat(listed).extracting(PublicationObjectSource.ListedObject::key).containsExactly("live/a", "live/b");
        assertThat(listed.get(0).lastModified()).isEqualTo(t0);
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.bucket()).isEqualTo("synthetic-publication"); assertThat(request.prefix()).isEqualTo("live/"); assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
            assertThat(request.maxKeys()).isEqualTo(PublicationBootstrap.MAX_LIVE_OBJECTS + 1);
        });
        assertThat(requests.get(1).continuationToken()).isEqualTo("next");
        assertThat(new String(source.open("live/a/manifest.json").readAllBytes())).isEqualTo("live/a/manifest.json");
        // An endless listing is cut off instead of being read into memory.
        when(client.listObjectsV2(any(java.util.function.Consumer.class))).thenAnswer(invocation ->
            software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                .contents(java.util.stream.IntStream.range(0, 40).mapToObj(i -> software.amazon.awssdk.services.s3.model.S3Object.builder().key("live/x" + i).lastModified(t0).build()).toList())
                .isTruncated(true).nextContinuationToken("more").build());
        assertThatThrownBy(() -> source.list("live/")).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void cloudBeansInitializeVerifiedPublicationAndReadinessWithControlledObjectSource(String mode) throws Exception {
        var helper = new PublicationBootstrapTest(); helper.directory=directory;
        var fixture = helper.fixture(null,false,false);
        var guard = new CloudRuntimeGuardTest(); guard.directory=directory;
        var environment = guard.valid();
        // Cloud production directory is Linux /tmp; fixture constructor uses the real test path.
        var selected = helper.settings(fixture);
        new ApplicationContextRunner().withUserConfiguration(PublicationConfiguration.Cloud.class)
                .withBean(PublicationSettings.class,()->selected)
                .withBean("syntheticObjects",PublicationObjectSource.class,()->helper.source(fixture),definition->definition.setPrimary(true))
                .withPropertyValues("app.deployment-environment="+mode, "app.publication.region=us-east-2", "server.address=127.0.0.1",
                        "spring.datasource.password="+environment.getProperty("spring.datasource.password"),
                        "spring.datasource.url="+environment.getProperty("spring.datasource.url"))
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(S3Client.class).hasSingleBean(ContainerCredentialsProvider.class);
                    var configuration = new PublicationConfiguration();
                    var location = configuration.publicationLocation(selected,context.getEnvironment(),context.getBeanProvider(PublicationObjectSource.class),new JsonMapper());
                    var policy = new ProtectedContentPolicy(new JsonMapper(),location);
                    var catalog = new SnapshotValidator(new JsonMapper(),location);
                    assertThat(configuration.contentPublication(location,policy,catalog).health().getStatus().getCode()).isEqualTo("UP");
                });
        helper.writableForFixtureCleanup();
    }

    @ParameterizedTest @ValueSource(strings={"missing", "blank", "repeated", "advancing-empty"})
    void incompleteOrNonProgressingListingsFailClosedWithinBoundedRequests(String mode) {
        var client = mock(S3Client.class);
        var settings = new PublicationSettings(true,"us-east-2","123456789012","synthetic-publication",null,null,null,Path.of("/tmp/lookahead-publications"),1024,4);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.listObjectsV2(any(java.util.function.Consumer.class))).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call > PublicationBootstrap.MAX_LIVE_OBJECTS + 1)
                throw new AssertionError("Publication pagination exceeded its request bound");
            var page = software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder().isTruncated(true);
            if (mode.equals("blank")) page.nextContinuationToken(" ");
            if (mode.equals("repeated")) page.nextContinuationToken("same-token");
            if (mode.equals("advancing-empty")) page.nextContinuationToken("token-" + call);
            return page.build();
        });
        var source = new PublicationConfiguration.Cloud().publicationObjects(client, settings);
        assertThatThrownBy(() -> source.list("live/")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloud publication listing is incomplete or exceeds its request bound");
        assertThat(calls.get()).isLessThanOrEqualTo(PublicationBootstrap.MAX_LIVE_OBJECTS + 1);
        if (mode.equals("missing") || mode.equals("blank")) assertThat(calls.get()).isEqualTo(1);
        if (mode.equals("repeated")) assertThat(calls.get()).isEqualTo(2);
    }

    @Test void emptyPagesWithAdvancingTokensCanReachACompleteListing() throws Exception {
        var client = mock(S3Client.class);
        var settings = new PublicationSettings(true,"us-east-2","123456789012","synthetic-publication",null,null,null,Path.of("/tmp/lookahead-publications"),1024,4);
        // Counted thenAnswer like the sibling tests: thenReturn and verify both call MockUtil.isMock, whose mock-maker
        // assertion (Mockito 5.23, surefire runs with -ea) fails for this S3Client mock once another mock maker is registered.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.listObjectsV2(any(java.util.function.Consumer.class))).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                    .isTruncated(true).nextContinuationToken("next-page").build()
                : software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder().isTruncated(false)
                    .contents(software.amazon.awssdk.services.s3.model.S3Object.builder()
                        .key("live/v2026.10.30.00.00/manifest.json").lastModified(java.time.Instant.EPOCH).build()).build());
        var source = new PublicationConfiguration.Cloud().publicationObjects(client, settings);
        assertThat(source.list("live/")).extracting(PublicationObjectSource.ListedObject::key)
                .containsExactly("live/v2026.10.30.00.00/manifest.json");
        assertThat(calls.get()).isEqualTo(2);
    }
}
