package com.lookahead.domain.db.store.plan;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.n;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.s;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest;
import software.amazon.awssdk.services.dynamodb.model.ParameterizedStatement;

/**
 * One account record + immutable administrator event + durable replay receipt in one PartiQL
 * transaction. A library, not a Spring bean: {@code StudyPlanDocumentPublishStore} publishes and
 * retires large plan documents through it and the document store adds its own statements to the
 * same transaction. Authorization and multi-record aggregate invariants belong to the calling
 * store; this primitive does not grant access. A lost condition is {@link DynamoWrites.Conflict}
 * (409), any other SDK failure {@link DynamoWrites.Unavailable} (503) after the durable receipt has
 * been consulted.
 */
public final class AccountAuditedStore {
  public record AuditEvent(String actor, String action, String reason, Instant occurredAt) {
    public AuditEvent {
      bounded(actor, 256, "actor");
      bounded(action, 128, "action");
      bounded(reason, 2048, "reason");
      Objects.requireNonNull(occurredAt, "occurredAt");
      if (occurredAt.isBefore(Instant.EPOCH)
          || occurredAt.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) {
        throw new IllegalArgumentException("Audit timestamp outside supported range");
      }
    }
  }

  /** expectedRevision zero means create; positive means replace that exact revision. */
  public record Mutation(
      UUID accountId,
      String recordId,
      UUID operationId,
      long expectedRevision,
      SdkBytes payload,
      AuditEvent audit) {
    public Mutation {
      Objects.requireNonNull(accountId, "accountId");
      bounded(recordId, 256, "recordId");
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(payload, "payload");
      Objects.requireNonNull(audit, "audit");
      if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Invalid expected revision");
      }
      int length = payload.asByteArray().length;
      if (length < 1 || length > StudyPlanDocumentCodec.CHUNK_BYTES) {
        throw new IllegalArgumentException("Account payload must be 1 byte through 240 KiB");
      }
      payload = SdkBytes.fromByteArray(payload.asByteArray());
    }
  }

  public record StoredRecord(long revision, SdkBytes payload) {}

  public record CommitResult(long revision, boolean replayed) {}

  private final DynamoDbClient client;
  private final DynamoTableNames tables;

  public AccountAuditedStore(DynamoDbClient client, DynamoTableNames tables) {
    this.client = Objects.requireNonNull(client, "client");
    this.tables = Objects.requireNonNull(tables, "tables");
  }

  public Optional<StoredRecord> read(UUID accountId, String recordId) {
    Objects.requireNonNull(accountId, "accountId");
    bounded(recordId, 256, "recordId");
    var item = get(accountKey(accountId), recordKey(recordId));
    if (item.isEmpty()) return Optional.empty();
    requireSchema(item);
    var payload = item.get("payload");
    long revision = positiveNumber(item, "revision");
    if (payload == null
        || payload.b() == null
        || payload.b().asByteArray().length == 0
        || payload.b().asByteArray().length > StudyPlanDocumentCodec.CHUNK_BYTES) {
      throw new IllegalStateException("Invalid stored account payload");
    }
    return Optional.of(new StoredRecord(revision, payload.b()));
  }

  public CommitResult commit(Mutation mutation) {
    return commitDocument(mutation, null);
  }

  CommitResult commitDocument(Mutation mutation, StudyPlanDocumentStore.VerifiedDocument document) {
    if (document != null && document.replayOnly())
      return requireReplay(mutation, document.fingerprintScope());
    return commitPublication(
        mutation,
        document == null ? "record/v1" : document.fingerprintScope(),
        document == null ? List.of() : document.publicationStatements(tables));
  }

  CommitResult retireDocument(
      Mutation mutation, StudyPlanDocumentRetirementStore.VerifiedRetirement retirement) {
    if (retirement.replayOnly()) return requireReplay(mutation, retirement.fingerprintScope());
    return commitPublication(
        mutation, retirement.fingerprintScope(), retirement.statements(tables));
  }

  private CommitResult requireReplay(Mutation mutation, String scope) {
    return receipt(mutation, publicationFingerprint(mutation, scope))
        .orElseThrow(
            () -> new IllegalStateException("Completed document operation has no durable receipt"));
  }

  private CommitResult commitPublication(
      Mutation mutation, String scope, List<ParameterizedStatement> publication) {
    Objects.requireNonNull(mutation, "mutation");
    String fingerprint = publicationFingerprint(mutation, scope);
    var previous = receipt(mutation, fingerprint);
    if (previous.isPresent()) return previous.get();
    try {
      var request = request(mutation, fingerprint);
      if (!publication.isEmpty()) {
        var statements = new ArrayList<>(request.transactStatements());
        statements.addAll(publication);
        request = request.toBuilder().transactStatements(statements).build();
      }
      client.executeTransaction(request);
      return new CommitResult(mutation.expectedRevision() + 1, false);
    } catch (SdkException failure) {
      // Includes lost responses and duplicate concurrent retries. No new operation ID or
      // blind compensation: only the transaction's durable receipt proves a commit. Without one the
      // failure is classified: a lost condition is the 409 conflict, anything else the 503 outage.
      RuntimeException classified = DynamoWrites.classify(failure);
      try {
        var recovered = receipt(mutation, fingerprint);
        if (recovered.isPresent()) return recovered.get();
      } catch (RuntimeException reconciliationFailure) {
        classified.addSuppressed(reconciliationFailure);
      }
      throw classified;
    }
  }

  private static String publicationFingerprint(Mutation mutation, String scope) {
    return Sha256.hex((fingerprint(mutation) + "|" + scope).getBytes(StandardCharsets.UTF_8));
  }

  private Optional<CommitResult> receipt(Mutation mutation, String fingerprint) {
    var item = get(accountKey(mutation.accountId()), "MUTATION#" + mutation.operationId());
    if (item.isEmpty()) return Optional.empty();
    requireSchema(item);
    if (!s(fingerprint).equals(item.get("fingerprint"))) {
      throw new IllegalStateException("Operation ID already used for a different mutation");
    }
    long revision = positiveNumber(item, "revision");
    if (revision != mutation.expectedRevision() + 1) {
      throw new IllegalStateException("Invalid stored mutation receipt");
    }
    return Optional.of(new CommitResult(revision, true));
  }

  private Map<String, AttributeValue> get(String pk, String sk) {
    return DynamoItems.strongGet(client, tables.userAccountDetailsTableName(), pk, sk);
  }

  private ExecuteTransactionRequest request(Mutation m, String fingerprint) {
    String pk = accountKey(m.accountId());
    String table = '"' + tables.userAccountDetailsTableName() + '"';
    long revision = m.expectedRevision() + 1;
    ParameterizedStatement record;
    if (m.expectedRevision() == 0) {
      record =
          statement(
              "INSERT INTO "
                  + table
                  + " VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'revision': ?, 'payload': ?}",
              s(pk),
              s(recordKey(m.recordId())),
              n(revision),
              binary(m.payload()));
    } else {
      record =
          statement(
              "UPDATE "
                  + table
                  + " SET revision=? SET payload=?"
                  + " WHERE pk=? AND sk=? AND revision=? AND schemaVersion=1",
              n(revision),
              binary(m.payload()),
              s(pk),
              s(recordKey(m.recordId())),
              n(m.expectedRevision()));
    }
    String time =
        new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter()
            .format(m.audit().occurredAt());
    var audit =
        statement(
            "INSERT INTO \""
                + tables.administratorAuditEventsTableName()
                + "\" VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'operationId': ?, 'recordId': ?,"
                + " 'actor': ?, 'action': ?, 'reason': ?, 'occurredAt': ?, 'revision': ?}",
            s(pk),
            s("EVENT#" + time + "#" + m.operationId()),
            s(m.operationId().toString()),
            s(m.recordId()),
            s(m.audit().actor()),
            s(m.audit().action()),
            s(m.audit().reason()),
            s(time),
            n(revision));
    var receipt =
        statement(
            "INSERT INTO "
                + table
                + " VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'fingerprint': ?, 'revision': ?}",
            s(pk),
            s("MUTATION#" + m.operationId()),
            s(fingerprint),
            n(revision));
    // Three distinct items, bounded well below the item and aggregate transaction size limits.
    return ExecuteTransactionRequest.builder()
        .transactStatements(record, audit, receipt)
        .clientRequestToken(
            UUID.nameUUIDFromBytes(
                    (m.accountId() + ":" + m.operationId()).getBytes(StandardCharsets.UTF_8))
                .toString())
        .build();
  }

  private static String fingerprint(Mutation m) {
    // Length prefixes avoid ambiguous strings and include all stored caller-controlled facts.
    var input = new StringBuilder("account-mutation/v1");
    for (String value :
        List.of(
            m.accountId().toString(),
            m.recordId(),
            m.operationId().toString(),
            Long.toString(m.expectedRevision()),
            Sha256.hex(m.payload().asByteArray()),
            m.audit().actor(),
            m.audit().action(),
            m.audit().reason(),
            m.audit().occurredAt().toString())) {
      input
          .append('|')
          .append(value.getBytes(StandardCharsets.UTF_8).length)
          .append(':')
          .append(value);
    }
    return Sha256.hex(input.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String accountKey(UUID id) {
    return "ACCOUNT#" + id;
  }

  private static String recordKey(String id) {
    return "RECORD#v1#" + id.getBytes(StandardCharsets.UTF_8).length + ":" + id;
  }

  private static AttributeValue binary(SdkBytes value) {
    return AttributeValue.builder().b(value).build();
  }

  private static ParameterizedStatement statement(String sql, AttributeValue... parameters) {
    return ParameterizedStatement.builder().statement(sql).parameters(parameters).build();
  }

  private static void requireSchema(Map<String, AttributeValue> item) {
    if (!n(1).equals(item.get("schemaVersion"))) {
      throw new IllegalStateException("Unsupported DynamoDB item schema");
    }
  }

  private static long positiveNumber(Map<String, AttributeValue> item, String name) {
    try {
      long value = Long.parseLong(item.get(name).n());
      if (value > 0) return value;
    } catch (NullPointerException | NumberFormatException invalid) {
      throw new IllegalStateException("Invalid stored " + name, invalid);
    }
    throw new IllegalStateException("Invalid stored " + name);
  }

  private static void bounded(String value, int maximum, String field) {
    if (value == null
        || value.isBlank()
        || value.getBytes(StandardCharsets.UTF_8).length > maximum
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException("Invalid " + field);
    }
  }
}
