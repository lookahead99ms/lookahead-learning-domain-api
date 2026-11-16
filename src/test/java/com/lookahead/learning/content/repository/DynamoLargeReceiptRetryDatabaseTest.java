package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-970 (P5), real DynamoDB Local: a mutation whose response (above the inline budget) was published as a document
 * before its own owner transaction lost the fence is retried by the client with the same key, later, under a new
 * staging deadline. The published document is reused as it is (same content, its original deadline) and the retry
 * succeeds instead of being refused for ever with "Document ID reused with different content or deadline".
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoLargeReceiptRetryDatabaseTest {
    private static DynamoDbClient client;
    private static final String ACCOUNTS = DynamoLocalFixture.TABLES.userAccountDetailsTableName();
    private static final String STUDY = DynamoLocalFixture.TABLES.userStudyPlanDetailsTableName();
    private final JsonMapper mapper = new JsonMapper();
    private final DynamoLocalFixture.MutableClock clock = new DynamoLocalFixture.MutableClock(Instant.parse("2026-11-10T06:00:00Z"));
    private final DynamoPlanRepository repository = new DynamoPlanRepository(client, DynamoLocalFixture.TABLES, mapper, clock);
    private final TransactionTemplate transactions = new TransactionTemplate(new DynamoOwnerTransactionManager(client, DynamoLocalFixture.TABLES, clock));

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }

    @Test void aRetriedMutationReplaysTheResponseDocumentPublishedBeforeItsFailedCommit() {
        UUID owner = DynamoLocalFixture.owner(client), key = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID();
        ObjectNode big = mapper.createObjectNode().put("planId", plan.toString()).put("filler", "x".repeat(DynamoPlanRepository.INLINE_BYTES + 1024));
        UUID documentId = DynamoPlanDocuments.operation("plan-receipt", owner, key.toString());
        String manifestPk = "DOC#" + owner + "#" + documentId;
        long firstDeadline = clock.instant().getEpochSecond() + DynamoPlanDocuments.STAGE_SECONDS;

        // First attempt: the response document is staged and published, then the owner fence is lost before the unit commits.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            repository.lockMutation(owner, key);
            repository.createPlan(owner, plan, version, "Big", mapper.createObjectNode(), "Big plan", repository.allocatePlanNumber(owner));
            repository.insertReceipt(owner, key, "sha256:big", plan, big, 201);
            client.updateItem(b -> b.tableName(ACCOUNTS).key(DynamoItems.key(DynamoItems.account(owner), "META"))
                    .updateExpression("SET revision = revision + :one").expressionAttributeValues(Map.of(":one", DynamoItems.n(1))));
        })).isInstanceOf(DynamoWrites.Conflict.class);
        var manifest = DynamoLocalFixture.item(client, STUDY, manifestPk, "MANIFEST");
        assertThat(manifest.get("lifecycle").s()).isEqualTo("COMMITTED");
        assertThat(DynamoItems.number(manifest, "abandonAfter")).isEqualTo(firstDeadline);
        assertThat(repository.findReceipt(owner, key)).isEmpty();
        assertThat(DynamoLocalFixture.items(client, DynamoItems.account(owner), "PLAN#")).isEmpty();

        // The client retries the same mutation key five minutes later: a new staging deadline, the same bytes.
        clock.advance(Duration.ofMinutes(5));
        transactions.executeWithoutResult(status -> {
            repository.lockMutation(owner, key);
            repository.createPlan(owner, plan, version, "Big", mapper.createObjectNode(), "Big plan", repository.allocatePlanNumber(owner));
            repository.insertReceipt(owner, key, "sha256:big", plan, big, 201);
        });
        var receipt = repository.findReceipt(owner, key).orElseThrow();
        assertThat(receipt.status()).isEqualTo(201);
        assertThat(receipt.response().path("filler").asText()).hasSize(DynamoPlanRepository.INLINE_BYTES + 1024);
        var stored = DynamoLocalFixture.item(client, ACCOUNTS, DynamoItems.account(owner), "MUTATION#" + key);
        assertThat(DynamoItems.uuid(stored, "documentId")).isEqualTo(documentId);
        // The receipt references the document as published: one manifest, the original deadline, one publication audit event.
        assertThat(DynamoItems.number(stored, "abandonAfter")).isEqualTo(firstDeadline);
        assertThat(DynamoItems.number(DynamoLocalFixture.item(client, STUDY, manifestPk, "MANIFEST"), "abandonAfter")).isEqualTo(firstDeadline);
        assertThat(DynamoOwnerReads.queryAll(client, DynamoLocalFixture.TABLES.administratorAuditEventsTableName(), DynamoItems.account(owner), "EVENT#")).hasSize(1);
        // Replaying the finished mutation reads the same answer; the document is still one document.
        assertThat(repository.findReceipt(owner, key).orElseThrow().response()).isEqualTo(receipt.response());
        assertThat(DynamoOwnerReads.queryAll(client, STUDY, manifestPk, "CHUNK#")).isNotEmpty();
    }
}
