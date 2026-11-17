package com.lookahead.learning.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.json.JsonMapper;

/** DLV-968: plan access checks see curriculum grants only; the staff documents scope authorizes no plan content. */
class PlanRepositoryGrantsTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final UUID account = UUID.randomUUID();

    @Test void staffScopeIsDroppedFromPlanGrants() {
        when(accounts.findTopicGrants(account)).thenReturn(Set.of("staff:documents", "learn:core-java", "blaze:system-design"));
        var repository = new DynamoPlanRepository(client, DynamoTableNames.local(), new JsonMapper());
        repository.accounts(accounts);
        assertThat(repository.grants(account)).containsExactlyInAnyOrder("blaze:system-design", "learn:core-java");
        verifyNoInteractions(client); // the grants come from the identity slice, never from a table read
    }
}
