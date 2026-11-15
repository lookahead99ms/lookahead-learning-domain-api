package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: dynamodb mode has no JDBC transaction manager; this one turns each PlanService transaction into one unit. */
@Configuration
@Profile("accounts")
@EnableTransactionManagement
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = com.lookahead.domain.persistence.PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoOwnerTransactionConfiguration {
    @Bean PlatformTransactionManager dynamoOwnerTransactionManager(DynamoDbClient client, DynamoTableNames tables) {
        return new DynamoOwnerTransactionManager(client, tables);
    }
}
