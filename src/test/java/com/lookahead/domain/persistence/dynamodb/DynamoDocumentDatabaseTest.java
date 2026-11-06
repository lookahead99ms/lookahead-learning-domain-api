package com.lookahead.domain.persistence.dynamodb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** Real DynamoDB Local transactions. Never resolves default credentials or an AWS endpoint. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoDocumentDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("dev");
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private final StudyPlanDocumentStore.DocumentId id = new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), UUID.randomUUID());
    private final StudyPlanDocumentStore store = storeAt(NOW);
    private final AuditedAccountStore accounts = new AuditedAccountStore(client, TABLES);
    private final AuditedAccountStore.AuditEvent event = new AuditedAccountStore.AuditEvent("account:synthetic", "plan.saved", "Synthetic plan", NOW);

    @BeforeAll static void createLocalTables() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1024 || endpoint.getPort() > 65535 || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null || !endpoint.getPath().isEmpty()) {
            throw new IllegalArgumentException("Only an explicit loopback fixture endpoint is allowed");
        }
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        for (String table : new String[]{TABLES.userAccountDetailsTableName(), TABLES.administratorAuditEventsTableName(),
                TABLES.userStudyPlanDetailsTableName(), TABLES.protectedOwnerIdentityTableName()}) {
            client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
        }
    }
    @AfterAll static void closeClient() { if (client != null) client.close(); }
    private static StudyPlanDocumentStore storeAt(Instant time) {
        return new StudyPlanDocumentStore(client, TABLES, Clock.fixed(time, ZoneOffset.UTC));
    }
    private StudyPlanDocumentStore.Stage stage(byte[] bytes) { return store.stage(id, bytes, NOW.plusSeconds(60)); }

    @Test void maximumDocumentPublishesPointerAuditReceiptAndManifestAtomically() {
        byte[] bytes = new byte[8 * 1024 * 1024]; new Random(970).nextBytes(bytes);
        var stage = stage(bytes);
        assertThat(stage.manifest().chunkCount()).isEqualTo(35);
        assertThatIllegalStateException().isThrownBy(() -> store.readPublished(id));
        assertThat(job(stage)).isNotEmpty();
        UUID operation = UUID.randomUUID();
        assertThat(store.publish(stage, "plan", operation, 0, event)).isEqualTo(new AuditedAccountStore.CommitResult(1, false));
        assertThat(store.readPublished(id)).isEqualTo(bytes);
        assertThat(accounts.read(id.ownerId(), "plan").orElseThrow().payload().asUtf8String())
                .contains(id.documentId().toString(), stage.manifest().sha256());
        assertThat(manifest().get("lifecycle")).isEqualTo(s("COMMITTED"));
        assertThat(job(stage)).isEmpty();
        assertThat(auditCount()).isEqualTo(1);
        assertThat(store.publish(stage, "plan", operation, 0, event).replayed()).isTrue();
        assertThat(storeAt(NOW.plusSeconds(120)).stage(id, bytes, NOW.plusSeconds(60))).isEqualTo(stage);
        assertThat(storeAt(NOW.plusSeconds(120)).cleanAbandoned(id)).isFalse();
        assertThatThrownBy(() -> store.writeChunk(stage, 0, SdkBytes.fromByteArray(new byte[245760])))
                .isInstanceOf(TransactionCanceledException.class);
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test void differentAccountCannotReadAnotherAccountsDocument() {
        var stage = stage(new byte[]{1}); store.publish(stage, "plan", UUID.randomUUID(), 0, event);
        assertThatIllegalStateException().isThrownBy(() -> store.readPublished(
                new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), id.documentId())));
    }

    @Test void repeatedChunksAreIdempotentButDifferentBytesCannotOverwrite() {
        var encoded = new StudyPlanDocumentCodec().encode(new byte[]{1, 2});
        var stage = new StudyPlanDocumentStore.Stage(id, encoded.manifest(), NOW.plusSeconds(60));
        store.begin(stage); store.begin(stage);
        store.writeChunk(stage, 0, encoded.chunks().getFirst());
        store.writeChunk(stage, 0, encoded.chunks().getFirst());
        assertThatThrownBy(() -> store.writeChunk(stage, 0, SdkBytes.fromByteArray(new byte[]{3, 4})))
                .isInstanceOf(TransactionCanceledException.class);
        assertThatIllegalStateException().isThrownBy(() -> store.stage(id, new byte[]{9, 9}, NOW.plusSeconds(60)));
        assertThatIllegalStateException().isThrownBy(() -> store.stage(id, new byte[]{1, 2}, NOW.plusSeconds(61)));
        store.publish(stage, "plan", UUID.randomUUID(), 0, event);
        assertThat(store.readPublished(id)).containsExactly(1, 2);
    }

    @Test void missingOrCorruptChunksNeverPublishAnything() {
        var encoded = new StudyPlanDocumentCodec().encode(new byte[]{1, 2});
        var stage = new StudyPlanDocumentStore.Stage(id, encoded.manifest(), NOW.plusSeconds(60));
        store.begin(stage);
        assertThatIllegalStateException().isThrownBy(() -> store.publish(stage, "plan", UUID.randomUUID(), 0, event));
        store.writeChunk(stage, 0, SdkBytes.fromByteArray(new byte[]{3, 4}));
        assertThatIllegalArgumentException().isThrownBy(() -> store.publish(stage, "plan", UUID.randomUUID(), 0, event));
        assertThat(accounts.read(id.ownerId(), "plan")).isEmpty();
        assertThat(auditCount()).isZero();
        assertThat(job(stage)).isNotEmpty();
        assertThat(manifest().get("lifecycle")).isEqualTo(s("STAGING"));
    }

    @Test void staleAccountRevisionRollsBackManifestAuditAndJobRemoval() {
        var stage = stage(new byte[]{1});
        assertThatThrownBy(() -> store.publish(stage, "plan", UUID.randomUUID(), 5, event))
                .isInstanceOf(TransactionCanceledException.class);
        assertThat(manifest().get("lifecycle")).isEqualTo(s("STAGING"));
        assertThat(job(stage)).isNotEmpty(); assertThat(auditCount()).isZero();
        store.publish(stage, "plan", UUID.randomUUID(), 0, event);
    }

    @Test void recordOnlyReceiptCannotStandInForDocumentPublication() {
        var stage = stage(new byte[]{1});
        UUID firstOperation = UUID.randomUUID();
        store.publish(stage, "first", firstOperation, 0, event);
        var pointer = accounts.read(id.ownerId(), "first").orElseThrow().payload();
        assertThatIllegalStateException().isThrownBy(() -> accounts.commit(new AuditedAccountStore.Mutation(
                id.ownerId(), "first", firstOperation, 0, pointer, event)));
        var otherId = new StudyPlanDocumentStore.DocumentId(id.ownerId(), UUID.randomUUID());
        var otherStage = store.stage(otherId, new byte[]{1}, NOW.plusSeconds(60));
        var otherPointer = SdkBytes.fromUtf8String(pointer.asUtf8String().replace(id.documentId().toString(), otherId.documentId().toString()));
        UUID operation = UUID.randomUUID();
        accounts.commit(new AuditedAccountStore.Mutation(id.ownerId(), "second", operation, 0, otherPointer, event));
        assertThatIllegalStateException().isThrownBy(() -> store.publish(otherStage, "second", operation, 0, event));
        assertThat(get(TABLES.userStudyPlanDetailsTableName(), otherId.pk(), "MANIFEST").get("lifecycle")).isEqualTo(s("STAGING"));
        assertThat(job(otherStage)).isNotEmpty();
    }

    @Test void deadlineIsRecheckedAfterChunkVerification() {
        var stage = stage(new byte[]{1});
        var reads = new AtomicInteger();
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return reads.incrementAndGet() == 1 ? NOW : NOW.plusSeconds(60); }
        };
        var expiredWhileVerifying = new StudyPlanDocumentStore(client, TABLES, moving);
        assertThatIllegalStateException().isThrownBy(() -> expiredWhileVerifying.publish(stage, "plan", UUID.randomUUID(), 0, event));
        assertThat(accounts.read(id.ownerId(), "plan")).isEmpty();
        assertThat(auditCount()).isZero();
    }

    @Test void interruptedChunkWriteResumesWithoutChangingManifestOrBytes() {
        var failOnce = new AtomicInteger();
        var interrupted = new StudyPlanDocumentStore(afterCallFailure("transactWriteItems", 2, failOnce), TABLES, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> interrupted.stage(id, new byte[]{1, 2, 3}, NOW.plusSeconds(60)))
                .isInstanceOf(SdkClientException.class);
        var stage = stage(new byte[]{1, 2, 3});
        store.publish(stage, "plan", UUID.randomUUID(), 0, event);
        assertThat(store.readPublished(id)).containsExactly(1, 2, 3);
    }

    @Test void unknownBeginAndPublicationResultsAreRecoveredFromDurableData() {
        var beginFailure = new StudyPlanDocumentStore(afterCallFailure("transactWriteItems", 1, new AtomicInteger()), TABLES, Clock.fixed(NOW, ZoneOffset.UTC));
        var stage = beginFailure.stage(id, new byte[]{1}, NOW.plusSeconds(60));
        var responseLost = new StudyPlanDocumentStore(afterCallFailure("executeTransaction", 1, new AtomicInteger()), TABLES, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID operation = UUID.randomUUID();
        assertThat(responseLost.publish(stage, "plan", operation, 0, event).replayed()).isTrue();
        assertThat(store.publish(stage, "plan", operation, 0, event).replayed()).isTrue();
        assertThat(auditCount()).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(() -> store.publish(stage, "other", operation, 0, event));
        assertThatIllegalStateException().isThrownBy(() -> store.publish(stage, "plan", UUID.randomUUID(), 0, event));
    }

    @Test void expiryCleanupIsBoundedResumableAndCannotResurrect() {
        var stage = stage(new byte[245761]);
        assertThat(store.cleanAbandoned(id)).isFalse();
        var expired = storeAt(NOW.plusSeconds(60));
        assertThatIllegalStateException().isThrownBy(() -> expired.publish(stage, "plan", UUID.randomUUID(), 0, event));
        assertThatIllegalStateException().isThrownBy(() -> expired.writeChunk(stage, 0, SdkBytes.fromByteArray(new byte[245760])));
        var interrupted = new StudyPlanDocumentStore(afterCallFailure("transactWriteItems", 1, new AtomicInteger()),
                TABLES, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        assertThatThrownBy(() -> interrupted.cleanAbandoned(id)).isInstanceOf(SdkClientException.class);
        assertThat(job(stage)).isNotEmpty();
        assertThat(expired.cleanAbandoned(id)).isTrue();
        assertThat(expired.cleanAbandoned(id)).isTrue();
        assertThat(job(stage)).isEmpty();
        assertThat(partition(TABLES.userStudyPlanDetailsTableName(), id.pk()).count()).isEqualTo(1);
        assertThat(manifest().get("lifecycle")).isEqualTo(s("DELETING"));
        assertThatIllegalStateException().isThrownBy(() -> store.begin(stage));
        assertThatThrownBy(() -> store.writeChunk(stage, 1, SdkBytes.fromByteArray(new byte[]{1})))
                .isInstanceOf(TransactionCanceledException.class);
        assertThatIllegalStateException().isThrownBy(() -> store.publish(stage, "plan", UUID.randomUUID(), 0, event));
        assertThat(store.cleanAbandoned(new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), UUID.randomUUID()))).isFalse();
    }

    @Test void cleanupAndPublicationRaceHasOnlyOneWinner() throws Exception {
        var stage = stage(new byte[]{1});
        var barrier = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var published = executor.submit(() -> race(barrier, () -> store.publish(stage, "plan", UUID.randomUUID(), 0, event)));
            var cleaned = executor.submit(() -> race(barrier, () -> storeAt(NOW.plusSeconds(60)).cleanAbandoned(id)));
            barrier.countDown(); published.get(); cleaned.get();
        }
        if (s("COMMITTED").equals(manifest().get("lifecycle"))) {
            assertThat(store.readPublished(id)).containsExactly(1);
            assertThat(auditCount()).isEqualTo(1);
        } else {
            storeAt(NOW.plusSeconds(60)).cleanAbandoned(id);
            assertThat(accounts.read(id.ownerId(), "plan")).isEmpty();
            assertThat(auditCount()).isZero();
            assertThat(partition(TABLES.userStudyPlanDetailsTableName(), id.pk()).count()).isEqualTo(1);
        }
    }

    @Test void competingPublicationsCannotBothCommit() throws Exception {
        var stage = stage(new byte[]{1});
        var barrier = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> race(barrier, () -> store.publish(stage, "a", UUID.randomUUID(), 0, event)));
            var second = executor.submit(() -> race(barrier, () -> store.publish(stage, "b", UUID.randomUUID(), 0, event)));
            barrier.countDown(); first.get(); second.get();
        }
        assertThat((accounts.read(id.ownerId(), "a").isPresent() ? 1 : 0) +
                (accounts.read(id.ownerId(), "b").isPresent() ? 1 : 0)).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test void ordinaryAccountRevisionAndInsertOnlyAuditAreEnforcedByDatabase() {
        var create = new AuditedAccountStore.Mutation(id.ownerId(), "settings", UUID.randomUUID(), 0, SdkBytes.fromUtf8String("one"), event);
        accounts.commit(create);
        var update = new AuditedAccountStore.Mutation(id.ownerId(), "settings", UUID.randomUUID(), 1, SdkBytes.fromUtf8String("two"), event);
        accounts.commit(update);
        assertThat(accounts.read(id.ownerId(), "settings").orElseThrow().revision()).isEqualTo(2);
        assertThat(accounts.commit(update).replayed()).isTrue();
        assertThatThrownBy(() -> accounts.commit(new AuditedAccountStore.Mutation(id.ownerId(), "settings", UUID.randomUUID(), 1,
                SdkBytes.fromUtf8String("lost"), event))).isInstanceOf(TransactionCanceledException.class);
        assertThat(auditCount()).isEqualTo(2);
        var audit = partition(TABLES.administratorAuditEventsTableName(), "ACCOUNT#" + id.ownerId()).items().getFirst();
        assertThatThrownBy(() -> client.executeStatement(ExecuteStatementRequest.builder()
                .statement("INSERT INTO \"" + TABLES.administratorAuditEventsTableName() + "\" VALUE {'pk': ?, 'sk': ?}")
                .parameters(audit.get("pk"), audit.get("sk")).build())).isInstanceOf(DynamoDbException.class)
                .hasMessageContaining("Duplicate primary key")
                .satisfies(failure -> assertThat(((DynamoDbException) failure).statusCode()).isEqualTo(400));
        assertThat(get(TABLES.administratorAuditEventsTableName(), audit.get("pk").s(), audit.get("sk").s()))
                .isEqualTo(audit);
    }

    @Test void invalidInputsAndCorruptedManifestFailClosed() {
        var stage = stage(new byte[]{1});
        for (int index : new int[]{-1, 1}) assertThatIllegalArgumentException().isThrownBy(() -> store.writeChunk(stage, index, SdkBytes.fromByteArray(new byte[]{1})));
        assertThatIllegalArgumentException().isThrownBy(() -> store.writeChunk(stage, 0, SdkBytes.fromByteArray(new byte[2])));
        assertThatIllegalArgumentException().isThrownBy(() -> new StudyPlanDocumentStore.Stage(id, stage.manifest(), NOW.plusNanos(1)));
        assertThatIllegalStateException().isThrownBy(() -> storeAt(NOW.plusSeconds(61)).begin(stage));
        corruptManifest("lifecycle", s("invalid"));
        assertThatIllegalStateException().isThrownBy(() -> store.cleanAbandoned(id));
        corruptManifest("lifecycle", AttributeValue.builder().n("1").build());
        assertThatIllegalStateException().isThrownBy(() -> store.cleanAbandoned(id));
        corruptManifest("lifecycle", s("STAGING"));
        corruptManifest("byteLength", s("invalid"));
        assertThatIllegalStateException().isThrownBy(() -> store.begin(stage));
        corruptManifest("schemaVersion", AttributeValue.builder().n("2").build());
        assertThatIllegalStateException().isThrownBy(() -> store.readPublished(id));
    }

    private void corruptManifest(String name, AttributeValue value) {
        client.updateItem(UpdateItemRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName())
                .key(key(id.pk(), "MANIFEST")).updateExpression("SET #attribute = :value")
                .expressionAttributeNames(Map.of("#attribute", name)).expressionAttributeValues(Map.of(":value", value)).build());
    }
    private static boolean race(CountDownLatch barrier, Runnable action) throws Exception {
        barrier.await();
        try { action.run(); return true; }
        catch (TransactionCanceledException | ConditionalCheckFailedException | IllegalStateException expectedRace) { return false; }
    }
    private DynamoDbClient afterCallFailure(String methodName, int at, AtomicInteger count) {
        return (DynamoDbClient) Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(), new Class<?>[]{DynamoDbClient.class},
                (proxy, method, args) -> {
                    try {
                        var result = method.invoke(client, args);
                        if (method.getName().equals(methodName) && count.incrementAndGet() == at) throw SdkClientException.create("synthetic lost response");
                        return result;
                    } catch (InvocationTargetException target) { throw target.getCause(); }
                });
    }
    private Map<String, AttributeValue> manifest() { return get(TABLES.userStudyPlanDetailsTableName(), id.pk(), "MANIFEST"); }
    private Map<String, AttributeValue> job(StudyPlanDocumentStore.Stage stage) { return get(TABLES.userAccountDetailsTableName(), "WORK#DOCUMENT#00", stage.jobKey()); }
    private Map<String, AttributeValue> get(String table, String pk, String sk) {
        return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk, sk)).consistentRead(true).build()).item();
    }
    private QueryResponse partition(String table, String pk) {
        return client.query(QueryRequest.builder().tableName(table).keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", s(pk))).consistentRead(true).build());
    }
    private int auditCount() { return partition(TABLES.administratorAuditEventsTableName(), "ACCOUNT#" + id.ownerId()).count(); }
    private static Map<String, AttributeValue> key(String pk, String sk) { return Map.of("pk", s(pk), "sk", s(sk)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
}
