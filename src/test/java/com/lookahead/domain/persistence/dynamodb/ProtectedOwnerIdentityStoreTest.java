package com.lookahead.domain.persistence.dynamodb;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProtectedOwnerIdentityStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    private final ProtectedOwnerIdentityStore store = new ProtectedOwnerIdentityStore(client, DynamoTableNamesTest.names("prod"));
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private Map<String, AttributeValue> binding() {
        return new HashMap<>(Map.of("schemaVersion", AttributeValue.builder().n("1").build(),
                "issuer", s("https://synthetic.invalid/pool-a"), "subject", s("subject-a")));
    }

    @Test void bothIssuerAndSubjectMustMatchExactlyAndOnlyGetIsUsed() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(binding()).build());
        assertThat(store.matchesVerifiedIdentity("https://synthetic.invalid/pool-a", "subject-a")).isTrue();
        assertThat(store.matchesVerifiedIdentity("https://synthetic.invalid/pool-b", "subject-a")).isFalse();
        assertThat(store.matchesVerifiedIdentity("https://synthetic.invalid/pool-a", "subject-b")).isFalse();
        assertThat(store.matchesVerifiedIdentity("https://synthetic.invalid/pool-a/", "subject-a")).isFalse();
        var requests = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(4)).getItem(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(r -> {
            assertThat(r.tableName()).isEqualTo("lookahead-prod-protected-owner-identity");
            assertThat(r.consistentRead()).isTrue();
            assertThat(r.key()).isEqualTo(Map.of("pk", s("OWNER"), "sk", s("ROOT")));
        });
        verifyNoMoreInteractions(client);
    }

    @Test void blankInputsCannotTriggerOwnerLookupOrBootstrap() {
        for (String invalid : new String[]{null, "", " "}) {
            assertThat(store.matchesVerifiedIdentity(invalid, "subject")).isFalse();
            assertThat(store.matchesVerifiedIdentity("issuer", invalid)).isFalse();
        }
        verifyNoInteractions(client);
    }

    @Test void missingBindingDeniesAndReadFailurePropagatesWithoutFallback() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertThat(store.matchesVerifiedIdentity("issuer", "subject")).isFalse();
        var failure = SdkClientException.create("synthetic unavailable");
        when(client.getItem(any(GetItemRequest.class))).thenThrow(failure);
        assertThatThrownBy(() -> store.matchesVerifiedIdentity("issuer", "subject")).isSameAs(failure);
    }

    @Test void corruptOrUnknownBindingFailsClosed() {
        var item = binding();
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> GetItemResponse.builder().item(item).build());
        item.put("schemaVersion", AttributeValue.builder().n("2").build());
        assertThatIllegalStateException().isThrownBy(() -> store.matchesVerifiedIdentity("issuer", "subject"));
        item.putAll(binding());
        for (String key : new String[]{"issuer", "subject"}) {
            for (AttributeValue invalid : new AttributeValue[]{s(" "), AttributeValue.builder().n("1").build()}) {
                item.put(key, invalid);
                assertThatIllegalStateException().isThrownBy(() -> store.matchesVerifiedIdentity("issuer", "subject"));
                item.putAll(binding());
            }
            item.remove(key);
            assertThatIllegalStateException().isThrownBy(() -> store.matchesVerifiedIdentity("issuer", "subject"));
            item.putAll(binding());
        }
    }
}
