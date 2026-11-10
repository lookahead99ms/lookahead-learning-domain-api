package com.lookahead.domain.persistence.dynamodb;

import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/** Read-only owner binding. Inputs must come from an already verified provider token. */
public final class ProtectedOwnerIdentityStore {
    private final DynamoDbClient client;
    private final DynamoMetadataTables tables;

    public ProtectedOwnerIdentityStore(DynamoDbClient client, DynamoTableNames tables) {
        this(client, DynamoMetadataTables.legacy(tables));
    }

    public ProtectedOwnerIdentityStore(DynamoDbClient client, DynamoMetadataTables tables) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    public boolean matchesVerifiedIdentity(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) return false;
        var item = client.getItem(GetItemRequest.builder().tableName(tables.protectedOwnerIdentityTableName())
                .key(Map.of("pk", s("OWNER"), "sk", s("ROOT"))).consistentRead(true).build()).item();
        if (item.isEmpty()) return false;
        if (!AttributeValue.builder().n("1").build().equals(item.get("schemaVersion"))) {
            throw new IllegalStateException("Unsupported protected owner binding schema");
        }
        var storedIssuer = item.get("issuer");
        var storedSubject = item.get("subject");
        if (storedIssuer == null || storedIssuer.s() == null || storedIssuer.s().isBlank()
                || storedSubject == null || storedSubject.s() == null || storedSubject.s().isBlank()) {
            throw new IllegalStateException("Invalid protected owner binding");
        }
        return s(issuer).equals(storedIssuer) && s(subject).equals(storedSubject);
    }

    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
}
