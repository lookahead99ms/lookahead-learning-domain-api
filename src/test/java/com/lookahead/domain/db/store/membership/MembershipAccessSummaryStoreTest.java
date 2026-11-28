package com.lookahead.domain.db.store.membership;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import tools.jackson.databind.ObjectMapper;

class MembershipAccessSummaryStoreTest {
  private final DynamoDbClient client =
      mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void anAccountWithoutGrantsHasNoneAndSnapshotsComeFromPurchases() {
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of()).build());
    var store =
        new MembershipAccessSummaryStore(
            client, DynamoTableNames.local(), json, (account, now) -> Set.of());
    assertThat(store.grantedTopics(UUID.randomUUID(), Instant.now())).isEmpty();
    assertThat(store.activeSnapshots(UUID.randomUUID(), Instant.now())).isEmpty();
    var wired =
        new MembershipAccessSummaryStore(
            client, DynamoTableNames.local(), json, (account, now) -> Set.of("learn:x"));
    assertThat(wired.grantedTopics(UUID.randomUUID(), Instant.now())).containsExactly("learn:x");
    var grants = new MembershipGrantsStore(client, DynamoTableNames.local(), json);
    assertThat(grants.catalogScopes(UUID.randomUUID())).isEmpty();
    assertThatNullPointerException()
        .isThrownBy(() ->
            new MembershipAccessSummaryStore(
                null, DynamoTableNames.local(), json, (account, now) -> Set.of()));
    // The grant lookup is a bean of every environment: a store built without it is a wiring
    // mistake, refused when it is built.
    assertThatNullPointerException()
        .isThrownBy(
            () -> new MembershipAccessSummaryStore(client, DynamoTableNames.local(), json, null));
  }
}
