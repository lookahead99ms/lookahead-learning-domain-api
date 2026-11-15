package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the DynamoDB store, active only with {@code app.persistence.store=dynamodb}. It replaces the
 * PostgreSQL data source, JDBC template, transaction manager and database health check, which are conditional
 * on {@code postgresql}. The readiness group lists both indicator names; the selected store contributes one.
 * No table is created here; the migration entrypoint and Infra own that.
 */
@Configuration
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoPersistenceConfiguration {
    @Bean DynamoSettings dynamoSettings(Environment environment) { return DynamoSettings.from(environment); }

    @Bean DynamoTableNames dynamoTableNames(DynamoSettings settings) { return settings.tables(); }

    @Bean(destroyMethod = "close") DynamoDbClient dynamoDbClient(DynamoSettings settings) { return settings.client(); }

    /** The release's data requirement, bound to the immutable catalog shipped in this build. */
    @Bean DynamoDataRequirement dynamoDataRequirement() { return DomainDynamoCatalog.catalog().requirement(); }

    @Bean("dynamodb") DynamoReadiness dynamoReadiness(DynamoDbClient client, DynamoTableNames tables, DynamoDataRequirement requirement) {
        return new DynamoReadiness(client, tables, requirement, Clock.systemUTC());
    }
}
