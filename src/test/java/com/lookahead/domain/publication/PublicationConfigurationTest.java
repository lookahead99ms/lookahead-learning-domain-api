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
}
