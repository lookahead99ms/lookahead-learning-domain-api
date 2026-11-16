package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: one numbered, immutable data step. A step gets the next consecutive number for every new table or
 * index, item family, key encoding, backfill or relied-on table setting. An applied number is never edited or
 * reused: the catalog hashes {@link #definition()} and the ledger keeps that checksum with the applied step.
 */
public interface DynamoMigrationStep {
    int version();

    /** Lower-case words joined by hyphens, as {@code DynamoDataRequirement} accepts. */
    String name();

    /** Stable text describing what the step changes and what {@link #verify} requires; it is hashed. */
    String definition();

    /** Idempotent: safe to repeat after a crash or lost reply; must protect concurrent application writes. */
    void apply(DynamoDbClient client, DynamoTableNames tables);

    /** Throws when the step's postconditions do not hold in the real store; read-only. */
    void verify(DynamoDbClient client, DynamoTableNames tables);
}
