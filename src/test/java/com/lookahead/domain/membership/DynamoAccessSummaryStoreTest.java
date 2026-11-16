package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoAccessSummaryStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final ObjectMapper json = new ObjectMapper();

    @Test @SuppressWarnings("unchecked") void withoutLaneAGrantsAreEmptyAndSnapshotsComeFromPurchases() {
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
        ObjectProvider<AccountGrantsLookup> absent = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.PROXY));
        when(absent.getIfAvailable()).thenReturn(null);
        var store = new DynamoAccessSummaryStore(client, DynamoTableNames.local(), json, absent);
        assertThat(store.grantedTopics(UUID.randomUUID(), Instant.now())).isEmpty();
        assertThat(store.activeSnapshots(UUID.randomUUID(), Instant.now())).isEmpty();
        var wired = new DynamoAccessSummaryStore(client, DynamoTableNames.local(), json, (account, now) -> Set.of("learn:x"));
        assertThat(wired.grantedTopics(UUID.randomUUID(), Instant.now())).containsExactly("learn:x");
        var grants = new DynamoMembershipGrants(client, DynamoTableNames.local(), json, mock(SnapshotValidator.class));
        assertThat(grants.topics(UUID.randomUUID())).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> new DynamoAccessSummaryStore(null, DynamoTableNames.local(), json, (AccountGrantsLookup) null));
    }
}
