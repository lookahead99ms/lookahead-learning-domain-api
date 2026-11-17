package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the mock provider's records as {@code MOCKPAYMENT#key / RECEIPT} plus a {@code MOCKPAYMENTID#id / KEY}
 * pointer, inserted together once; a second record under the same key loses to the first and reads it back.
 * A Local/DEV fixture concern only, never production membership data.
 */
@Component
public class DynamoMockPaymentStore implements MockPaymentStore {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    public DynamoMockPaymentStore(DynamoDbClient client, DynamoTableNames tables) { this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables); }
    private String table() { return tables.userAccountDetailsTableName(); }
    private static PaymentGateway.Result result(Map<String, AttributeValue> item) {
        if (item.isEmpty()) return null;
        DynamoItems.requireSchema(item, MembershipItems.SCHEMA);
        return new PaymentGateway.Result(DynamoItems.string(item, "paymentId"), DynamoItems.string(item, "status"),
                Math.toIntExact(DynamoItems.number(item, "amountCents")), DynamoItems.string(item, "currency"));
    }
    @Override public PaymentGateway.Result findByKey(String key) { return result(MembershipItems.get(client, table(), MembershipItems.mockPaymentPk(key), MembershipItems.RECEIPT_SK)); }
    @Override public PaymentGateway.Result record(String key, String paymentId, int amount, String currency, String status) {
        var attributes = Map.of("idempotencyKey", DynamoItems.s(key), "paymentId", DynamoItems.s(paymentId), "amountCents", DynamoItems.n(amount),
                "currency", DynamoItems.s(currency), "status", DynamoItems.s(status));
        try {
            DynamoWrites.transaction(client)
                    .insertOnly(table(), DynamoItems.item(MembershipItems.mockPaymentPk(key), MembershipItems.RECEIPT_SK, MembershipItems.SCHEMA, attributes))
                    .insertOnly(table(), DynamoItems.item(MembershipItems.mockPaymentIdPk(paymentId), "KEY", MembershipItems.SCHEMA, Map.of("idempotencyKey", DynamoItems.s(key))))
                    .commit();
        } catch (DynamoWrites.Conflict existing) { /* ON CONFLICT DO NOTHING: the first record under the key stands */ }
        return findByKey(key);
    }
    @Override public PaymentGateway.Result findById(String paymentId) {
        var pointer = MembershipItems.get(client, table(), MembershipItems.mockPaymentIdPk(paymentId), "KEY");
        return pointer.isEmpty() ? null : findByKey(DynamoItems.string(pointer, "idempotencyKey"));
    }
}
