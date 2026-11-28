package com.lookahead.domain.config;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the one {@code PlatformTransactionManager}; it turns each StudyPlanService transaction
 * into one DynamoDB unit.
 */
@Configuration
@Profile("accounts")
@EnableTransactionManagement
public class OwnerTransactionConfiguration {
  @Bean
  public PlatformTransactionManager ownerTransactionManager(
      DynamoDbClient client, DynamoTableNames tables) {
    return new OwnerTransactionManager(client, tables);
  }
}
