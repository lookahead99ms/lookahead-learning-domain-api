package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoMockPaymentStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final DynamoMockPaymentStore store = new DynamoMockPaymentStore(client, tables);

    @Test void aDuplicateKeyKeepsTheFirstRecordAndReadsItBack() {
        var stored = DynamoItems.item(MembershipItems.mockPaymentPk("k"), "RECEIPT", 1, Map.of("idempotencyKey", DynamoItems.s("k"), "paymentId", DynamoItems.s("pi_1"),
                "amountCents", DynamoItems.n(5), "currency", DynamoItems.s("USD"), "status", DynamoItems.s("succeeded")));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            String pk = request.key().get("pk").s();
            if (pk.equals(MembershipItems.mockPaymentPk("k"))) return GetItemResponse.builder().item(stored).build();
            if (pk.equals(MembershipItems.mockPaymentIdPk("pi_1"))) return GetItemResponse.builder().item(DynamoItems.item(pk, "KEY", 1, Map.of("idempotencyKey", DynamoItems.s("k")))).build();
            return GetItemResponse.builder().item(Map.of()).build();
        });
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("DuplicateItem").build(), CancellationReason.builder().code("None").build()).build());
        var first = new PaymentGateway.Result("pi_1", "succeeded", 5, "USD");
        assertThat(store.record("k", "pi_2", 5, "USD", "succeeded")).isEqualTo(first);
        assertThat(store.findByKey("k")).isEqualTo(first);
        assertThat(store.findById("pi_1")).isEqualTo(first);
        assertThat(store.findById("pi_9")).isNull();
        assertThat(store.findByKey("absent")).isNull();
        var gateway = new MockStripePaymentGateway(store, "success");
        assertThat(gateway.status("pi_1")).isEqualTo(first);
        assertThatThrownBy(() -> gateway.status("pi_9")).isInstanceOf(PaymentGateway.Failure.class);
        assertThatThrownBy(() -> gateway.create("k", 6, "USD")).isInstanceOf(PaymentGateway.Failure.class).hasMessage("idempotency-mismatch");
        assertThatThrownBy(() -> new MockStripePaymentGateway(store, "weird")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anUnavailableStoreIsNotSwallowed() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.create("down"));
        assertThatThrownBy(() -> store.record("k", "pi_2", 5, "USD", "succeeded")).isInstanceOf(DynamoWrites.Unavailable.class);
        assertThatNullPointerException().isThrownBy(() -> new DynamoMockPaymentStore(null, tables));
    }
}
