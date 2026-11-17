package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;

/** The one store: its beans exist unconditionally (DLV-970, PostgreSQL retired 2026-11-17) and refuse to start without their settings. */
class DynamoPersistenceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoPersistenceConfiguration.class)
            .withPropertyValues("spring.profiles.active=accounts");

    @Test void theStoreHasTheClientNamesRequirementAndReadiness() {
        runner.withPropertyValues("app.deployment-environment=local", "app.dynamodb.endpoint=http://127.0.0.1:4400").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DynamoDbClient.class).hasSingleBean(DynamoTableNames.class).hasSingleBean(DynamoSettings.class)
                    .hasSingleBean(DynamoDataRequirement.class).hasSingleBean(DynamoReadiness.class).hasBean("dynamodb");
            assertThat(context.getBean(DynamoDataRequirement.class).minimumVersion()).isEqualTo(DomainDynamoCatalog.REQUIRED);
            assertThat(context.getBean(DynamoTableNames.class)).isEqualTo(DynamoTableNames.local());
            assertThat(context.getBean("dynamodb")).isInstanceOf(HealthIndicator.class);
        });
    }

    @Test void devBindsTheFourExplicitNamesAndTheRegion() {
        runner.withPropertyValues("app.deployment-environment=dev", "app.dynamodb.region=us-east-2",
                "app.dynamodb.user-account-details-table-name=lookahead-dev-user-account-details",
                "app.dynamodb.administrator-audit-events-table-name=lookahead-dev-administrator-audit-events",
                "app.dynamodb.user-study-plan-details-table-name=lookahead-dev-user-study-plan-details",
                "app.dynamodb.protected-owner-identity-table-name=lookahead-dev-protected-owner-identity").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DynamoSettings.class).local()).isFalse();
            assertThat(context.getBean(DynamoTableNames.class)).isEqualTo(DynamoTableNamesTest.names("dev"));
        });
    }

    @Test void theStoreRefusesToStartWithoutItsConfiguration() {
        try (var logs = LogCapture.start("lookahead.startup")) {
            runner.withPropertyValues("app.deployment-environment=local").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessage("Local DynamoDB requires a loopback endpoint");
            });
            runner.withPropertyValues("app.deployment-environment=dev", "app.dynamodb.region=us-east-2").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessage("Missing or unapproved DynamoDB table name");
            });
            assertThat(logs.messages()).anyMatch(message -> message.contains(DynamoSettings.ENDPOINT_SETTING));
        }
    }
}
