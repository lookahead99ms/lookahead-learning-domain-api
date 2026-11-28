package com.lookahead.domain.config;

import com.lookahead.domain.db.store.dynamodb.DynamoDataRequirement;
import com.lookahead.domain.db.store.dynamodb.DynamoReadiness;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.health.DynamoReadinessIndicator;
import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the DynamoDB store, the one store of this Domain (PostgreSQL served DEV until 2026-11-17
 * and was retired with its data source, JDBC template, transaction manager and health check). The
 * readiness group names the {@code dynamodb} indicator registered here. No table is created here;
 * the migration entrypoint and Infra own that.
 */
@Configuration
public class DynamoPersistenceConfiguration {
  @Bean
  DynamoConnection dynamoConnection(Environment environment) {
    return DynamoConnection.from(environment);
  }

  @Bean
  DynamoTableNames dynamoTableNames(DynamoConnection connection) {
    return connection.tables();
  }

  /**
   * A synchronous client; building it opens no connection. DLV-1016: built by {@link
   * DynamoClientFactory}, which the data migration calls too.
   */
  @Bean(destroyMethod = "close")
  public DynamoDbClient dynamoDbClient(DynamoConnection connection) {
    return DynamoClientFactory.create(connection);
  }

  /** The release's data requirement, bound to the immutable catalog shipped in this build. */
  @Bean
  DynamoDataRequirement dynamoDataRequirement() {
    return DomainDynamoCatalog.catalog().requirement();
  }

  /** The store reads behind the readiness indicator; creating them reads nothing. */
  @Bean
  DynamoReadiness dynamoReadiness(
      DynamoDbClient client, DynamoTableNames tables, DynamoDataRequirement requirement) {
    return new DynamoReadiness(client, tables, requirement);
  }

  @Bean("dynamodb")
  DynamoReadinessIndicator dynamoReadinessIndicator(DynamoReadiness readiness) {
    return new DynamoReadinessIndicator(readiness, Clock.systemUTC());
  }
}
