package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the DynamoDB store, the one store of this Domain (PostgreSQL served DEV until 2026-11-17 and was retired
 * with its data source, JDBC template, transaction manager and health check). The readiness group names the
 * {@code dynamodb} indicator registered here. No table is created here; the migration entrypoint and Infra own that.
 */
@Configuration
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
