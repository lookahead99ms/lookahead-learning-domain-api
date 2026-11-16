package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.DomainDatabaseHealthIndicator;
import com.lookahead.domain.LogCapture;
import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import com.lookahead.learning.content.config.AccountDatabaseConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.*;

/** The store switch: exactly one store's beans exist, chosen by app.persistence.store, never both and never neither. */
class DynamoPersistenceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoPersistenceConfiguration.class, AccountDatabaseConfiguration.class, PersistenceStore.Check.class)
            .withPropertyValues("spring.profiles.active=accounts");

    @Test void dynamodbModeHasTheClientNamesRequirementAndReadinessAndNoJdbcBeans() {
        runner.withPropertyValues("app.persistence.store=dynamodb", "app.deployment-environment=local",
                "app.dynamodb.endpoint=http://127.0.0.1:4400").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DynamoDbClient.class).hasSingleBean(DynamoTableNames.class).hasSingleBean(DynamoSettings.class)
                    .hasSingleBean(DynamoDataRequirement.class).hasSingleBean(DynamoReadiness.class).hasBean("dynamodb");
            assertThat(context).doesNotHaveBean(DataSource.class).doesNotHaveBean(JdbcTemplate.class).doesNotHaveBean(DomainDatabaseHealthIndicator.class);
            assertThat(context.getBean(DynamoDataRequirement.class).minimumVersion()).isEqualTo(DomainDynamoCatalog.REQUIRED);
            assertThat(context.getBean(DynamoTableNames.class)).isEqualTo(DynamoTableNames.local());
            assertThat(context.getBean("dynamodb")).isInstanceOf(HealthIndicator.class);
            assertThat(context.getBean(PersistenceStore.Check.class).store()).isEqualTo(PersistenceStore.DYNAMODB);
        });
    }

    @Test void devDynamodbModeBindsTheFourExplicitNamesAndTheRegion() {
        runner.withPropertyValues("app.persistence.store=dynamodb", "app.deployment-environment=dev", "app.dynamodb.region=us-east-2",
                "app.dynamodb.user-account-details-table-name=lookahead-dev-user-account-details",
                "app.dynamodb.administrator-audit-events-table-name=lookahead-dev-administrator-audit-events",
                "app.dynamodb.user-study-plan-details-table-name=lookahead-dev-user-study-plan-details",
                "app.dynamodb.protected-owner-identity-table-name=lookahead-dev-protected-owner-identity").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DynamoSettings.class).local()).isFalse();
            assertThat(context.getBean(DynamoTableNames.class)).isEqualTo(DynamoTableNamesTest.names("dev"));
            assertThat(context).doesNotHaveBean(DataSource.class);
        });
    }

    @Test void dynamodbModeRefusesToStartWithoutItsConfiguration() {
        try (var logs = LogCapture.start("lookahead.startup")) {
            runner.withPropertyValues("app.persistence.store=dynamodb", "app.deployment-environment=local").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessage("Local DynamoDB requires a loopback endpoint");
            });
            runner.withPropertyValues("app.persistence.store=dynamodb", "app.deployment-environment=dev", "app.dynamodb.region=us-east-2").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessage("Missing or unapproved DynamoDB table name");
            });
            assertThat(logs.messages()).anyMatch(message -> message.contains(DynamoSettings.ENDPOINT_SETTING));
        }
    }

    @Test void postgresqlIsTheDefaultAndAnUnknownStoreStartsNeither() {
        // Default: the PostgreSQL configuration is selected; its data source refuses the missing JDBC URL, which proves the selection.
        runner.withPropertyValues("app.deployment-environment=local").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("accountDataSource");
        });
        new ApplicationContextRunner().withUserConfiguration(DynamoPersistenceConfiguration.class, PersistenceStore.Check.class)
                .withPropertyValues("app.deployment-environment=local").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DynamoDbClient.class).doesNotHaveBean(DynamoReadiness.class).doesNotHaveBean(DynamoSettings.class);
            assertThat(context.getBean(PersistenceStore.Check.class).store()).isEqualTo(PersistenceStore.POSTGRESQL);
        });
        try (var logs = LogCapture.start("lookahead.startup")) {
            runner.withPropertyValues("app.persistence.store=dynamo", "app.deployment-environment=local").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessage("Domain requires an explicit persistence store");
            });
            assertThat(logs.messages()).anyMatch(message -> message.contains(PersistenceStore.SETTING));
        }
    }
}
