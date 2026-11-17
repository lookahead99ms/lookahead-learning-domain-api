package com.lookahead.domain.review;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import static com.lookahead.domain.persistence.dynamodb.DynamoItems.*;
import static com.lookahead.domain.review.ReviewModels.*;

/**
 * DLV-970: author review events on DynamoDB (design row {@code author_review_events}).
 * <ul>
 *   <li>Administrator Audit Events, insert-only: the event {@code REVIEW#<actor>#<artifact> / EVENT#<sequence>#<eventId>}
 *   and its lookup {@code … / ID#<eventId>} (sequence of an event, for the history cursor). History is one strongly
 *   consistent Query of the partition, newest first.</li>
 *   <li>User Account Details: the artifact head {@code ACCOUNT#<actor> / REVIEW#<artifact>} (revision, the actor's last
 *   sequence on the artifact, the latest event) and the key receipt {@code ACCOUNT#<actor> / REVIEWKEY#<key>}
 *   (request hash and the event it recorded), both written in the same transaction as the event.</li>
 * </ul>
 * The sequence is per actor and artifact: PostgreSQL's global identity only ever ordered one actor's history on one
 * artifact. The head's revision is the lock the retired SQL store's {@code FOR UPDATE} on the actor row provided; the key receipt and
 * the event lookup are the two UNIQUE constraints; the latest event on the head is the one-successor index. The
 * actor must exist as {@code ACCOUNT#<actor> / META} (lane A's account record, schema 1), as the SQL foreign key on
 * the subject table required. A transaction that loses a race is re-evaluated from the store up to three
 * times, which turns the race into the business answer (replay, idempotency or supersession conflict).
 */
@Component
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name="app.deployment-environment",havingValue="local")
@ConditionalOnProperty(name="app.local-test.author-enabled",havingValue="true")
public class DynamoAuthorReviewStore implements AuthorReviewStore {
    static final int SCHEMA = 1, ACCOUNT_SCHEMA = 1, ATTEMPTS = 3;
    static final String RECONCILIATION = "PENDING_MAIN_RECONCILIATION", ACTION = "review.decided";
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    @Autowired
    public DynamoAuthorReviewStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }

    DynamoAuthorReviewStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static String eventsPk(UUID actorId, String artifactId) { return "REVIEW#" + actorId + "#" + encode(artifactId); }
    static String eventSk(long sequence, UUID eventId) { return "EVENT#" + padded(sequence) + "#" + eventId; }
    static String lookupSk(UUID eventId) { return "ID#" + eventId; }
    static String headSk(String artifactId) { return "REVIEW#" + encode(artifactId); }
    static String receiptSk(UUID key) { return "REVIEWKEY#" + key; }

    @Override public List<Event> latest(UUID actorId, String artifactId, int limit) {
        return history(actorId, artifactId, limit, "pk=:pk AND begins_with(sk, :from)", Map.of(":pk", s(eventsPk(actorId, artifactId)), ":from", s("EVENT#")));
    }

    @Override public Optional<List<Event>> before(UUID actorId, String artifactId, UUID cursor, int limit) {
        var lookup = get(tables.administratorAuditEventsTableName(), eventsPk(actorId, artifactId), lookupSk(cursor));
        if (lookup.isEmpty()) return Optional.empty();
        requireSchema(lookup, SCHEMA);
        // BETWEEN is inclusive; EVENT#<sequence> is shorter than the anchor's own key, so the anchor itself is excluded.
        String upper = "EVENT#" + padded(number(lookup, "sequence"));
        return Optional.of(history(actorId, artifactId, limit, "pk=:pk AND sk BETWEEN :from AND :to",
                Map.of(":pk", s(eventsPk(actorId, artifactId)), ":from", s("EVENT#"), ":to", s(upper))));
    }

    private List<Event> history(UUID actorId, String artifactId, int limit, String condition, Map<String, AttributeValue> values) {
        if (limit < 1) throw new IllegalArgumentException("History limit must be positive");
        var response = client.query(QueryRequest.builder().tableName(tables.administratorAuditEventsTableName()).consistentRead(true)
                .keyConditionExpression(condition).expressionAttributeValues(values).scanIndexForward(false).limit(limit).build());
        var events = new ArrayList<Event>();
        for (var item : response.items()) events.add(read(item));
        return List.copyOf(events);
    }

    @Override public Receipt submit(UUID actorId, String artifactId, UUID key, String hash, Artifact artifact, Submission normalized) {
        DynamoWrites.Conflict last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var receipt = get(tables.userAccountDetailsTableName(), account(actorId), receiptSk(key));
            if (!receipt.isEmpty()) return replay(actorId, receipt, hash);
            var head = get(tables.userAccountDetailsTableName(), account(actorId), headSk(artifactId));
            UUID expected = head.isEmpty() ? null : stringOrNull(head, "latestEventId") == null ? null : uuid(head, "latestEventId");
            if (!Objects.equals(expected, normalized.supersedesEventId())) {
                // Separate consistent reads are not a snapshot: a duplicate may have committed
                // its receipt and this head after our first receipt read. Reconcile that receipt
                // before rejecting supersession, preserving replay and changed-payload semantics.
                receipt = get(tables.userAccountDetailsTableName(), account(actorId), receiptSk(key));
                if (!receipt.isEmpty()) return replay(actorId, receipt, hash);
                throw new AccountFailure(409, "REVIEW_SUPERSESSION_CONFLICT", "Reload review history before changing the decision");
            }
            long sequence = (head.isEmpty() ? 0 : number(head, "sequence")) + 1;
            UUID eventId = UUID.randomUUID();
            Instant recordedAt = clock.instant();
            var event = new Event(eventId, artifactId, artifact.artifactVersion(), artifact.contentHash(), artifact.ticketId(),
                    normalized.decision(), normalized.comment(), actorId, recordedAt, key, expected);
            try {
                append(head, event, sequence, hash);
                return new Receipt(event, false, RECONCILIATION);
            } catch (DynamoWrites.Conflict conflict) {
                if (conflict.only(0)) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
                last = conflict; // Another writer moved first: read again and answer from what is now stored.
            }
        }
        throw last;
    }

    /** The five statements: actor exists, head insert or revision update, key receipt, event, event lookup. */
    private void append(Map<String, AttributeValue> head, Event event, long sequence, String hash) {
        String state = tables.userAccountDetailsTableName(), audit = tables.administratorAuditEventsTableName();
        var writes = DynamoWrites.transaction(client).check(state, key(account(event.actorId()), "META"), ACCOUNT_SCHEMA, null);
        var headKey = key(account(event.actorId()), headSk(event.artifactId()));
        if (head.isEmpty()) {
            writes.insertOnly(state, item(headKey.get("pk").s(), headKey.get("sk").s(), SCHEMA,
                    Map.of("revision", n(0), "sequence", n(sequence), "latestEventId", s(event.eventId().toString()), "updatedAt", s(event.recordedAt().toString()))));
        } else {
            requireSchema(head, SCHEMA);
            long revision = number(head, "revision");
            writes.updateAtRevision(state, headKey, Map.of("revision", n(revision + 1), "sequence", n(sequence),
                    "latestEventId", s(event.eventId().toString()), "updatedAt", s(event.recordedAt().toString())), revision);
        }
        writes.insertOnly(state, item(account(event.actorId()), receiptSk(event.idempotencyKey()), SCHEMA, Map.of("requestHash", s(hash),
                "artifactId", s(event.artifactId()), "eventId", s(event.eventId().toString()), "sequence", n(sequence), "occurredAt", s(event.recordedAt().toString()))));
        var attributes = new HashMap<String, AttributeValue>();
        attributes.put("eventId", s(event.eventId().toString())); attributes.put("artifactId", s(event.artifactId()));
        attributes.put("artifactVersion", s(event.artifactVersion())); attributes.put("contentHash", s(event.contentHash()));
        attributes.put("ticketId", s(event.ticketId())); attributes.put("decision", s(event.decision().name()));
        attributes.put("comment", s(event.comment())); attributes.put("actorId", s(event.actorId().toString()));
        attributes.put("idempotencyKey", s(event.idempotencyKey().toString()));
        attributes.put("supersedesEventId", event.supersedesEventId() == null ? null : s(event.supersedesEventId().toString()));
        attributes.put("sequence", n(sequence));
        // The audit conventions: who did what, when, with minimal facts. {@code occurredAt} is the API's recordedAt and the
        // one time attribute every audit item carries (memberships, resets, status history alike).
        attributes.put("actor", s(event.actorId().toString())); attributes.put("action", s(ACTION)); attributes.put("occurredAt", s(event.recordedAt().toString()));
        String pk = eventsPk(event.actorId(), event.artifactId());
        writes.insertOnly(audit, item(pk, eventSk(sequence, event.eventId()), SCHEMA, attributes));
        writes.insertOnly(audit, item(pk, lookupSk(event.eventId()), SCHEMA, Map.of("sequence", n(sequence), "eventId", s(event.eventId().toString()))));
        writes.commit();
    }

    private Receipt replay(UUID actorId, Map<String, AttributeValue> receipt, String hash) {
        requireSchema(receipt, SCHEMA);
        if (!string(receipt, "requestHash").equals(hash)) throw new AccountFailure(409, "REVIEW_IDEMPOTENCY_CONFLICT", "Use the original payload when retrying a review");
        var stored = get(tables.administratorAuditEventsTableName(), eventsPk(actorId, string(receipt, "artifactId")),
                eventSk(number(receipt, "sequence"), uuid(receipt, "eventId")));
        if (stored.isEmpty()) throw new IllegalStateException("Review receipt names an event that is not stored");
        return new Receipt(read(stored), true, RECONCILIATION);
    }

    private Map<String, AttributeValue> get(String table, String pk, String sk) {
        return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk, sk)).consistentRead(true).build()).item();
    }

    static Event read(Map<String, AttributeValue> item) {
        requireSchema(item, SCHEMA);
        return new Event(uuid(item, "eventId"), string(item, "artifactId"), string(item, "artifactVersion"), string(item, "contentHash"),
                string(item, "ticketId"), Decision.valueOf(string(item, "decision")), string(item, "comment"), uuid(item, "actorId"),
                instant(item, "occurredAt"), uuid(item, "idempotencyKey"), stringOrNull(item, "supersedesEventId") == null ? null : uuid(item, "supersedesEventId"));
    }
}
