package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: the one {@code PlatformTransactionManager}; it turns each PlanService transaction into one DynamoDB unit. */
@Configuration
@Profile("accounts")
@EnableTransactionManagement
public class DynamoOwnerTransactionConfiguration {
    @Bean PlatformTransactionManager dynamoOwnerTransactionManager(DynamoDbClient client, DynamoTableNames tables) {
        return new DynamoOwnerTransactionManager(client, tables);
    }
}
