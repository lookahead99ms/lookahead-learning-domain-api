package com.lookahead.domain.db.store.testaccounts;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-970 lane G: lane A's "is this a managed test account" is the registry row, whatever its creation state. */
class TestAccountRegistryStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final TestAccountRegistryStore registry = new TestAccountRegistryStore(client, tables);
    private final UUID account = UUID.randomUUID();

    @Test void theRegistryRowAnswersWhateverItsStateAndItsAbsenceIsNo() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        assertThat(registry.isTestAccount(account)).isFalse();
        var request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo(tables.userAccountDetailsTableName());
        assertThat(request.getValue().consistentRead()).isTrue();
        assertThat(request.getValue().key()).isEqualTo(DynamoItems.key(DynamoItems.account(account), TestAccountItems.REGISTRY_SK));
        for (String state : new String[] {"active", "deleted"}) {
            when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                    .item(DynamoItems.item(DynamoItems.account(account), TestAccountItems.REGISTRY_SK, 1, Map.of("creationState", DynamoItems.s(state)))).build());
            assertThat(registry.isTestAccount(account)).as(state).isTrue();
        }
    }
}
