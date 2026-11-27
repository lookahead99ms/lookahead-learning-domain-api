package com.lookahead.domain.db.store.dynamodb;

import com.lookahead.domain.model.HealthDiagnosis;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * The reads behind the {@code dynamodb} readiness indicator ({@code health.DynamoReadinessIndicator}, DLV-1016: the
 * indicator itself moved to the health package and this class kept the DynamoDB reads):
 * four bounded strong GetItem probes (one per table) and the data-version watermark against the release's
 * requirement. It never writes or migrates; a store behind the catalog reports
 * DATA_VERSION_BEHIND until the migration task has run.
 */
public final class DynamoReadiness {
    private static final Set<String> SAFE_CODES = Set.of("AccessDenied", "AccessDeniedException",
            "ResourceNotFoundException", "ProvisionedThroughputExceededException", "RequestLimitExceeded",
            "ThrottlingException", "InternalServerError", "ServiceUnavailable", "UnrecognizedClientException",
            "ExpiredTokenException", "ValidationException");
    private static final Map<String, AttributeValue> PROBE_KEY = Map.of(
            "pk", AttributeValue.builder().s("SYSTEM#READINESS").build(),
            "sk", AttributeValue.builder().s("PROBE").build());
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final DynamoDataVersionStore versions;
    private final DynamoDataRequirement requirement;

    public DynamoReadiness(DynamoDbClient client, DynamoTableNames tables, DynamoDataRequirement requirement) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.versions = new DynamoDataVersionStore(client, tables);
    }

    /** Null when every table answers and the data version meets the release's requirement; else the reason, without secrets. */
    public HealthDiagnosis diagnose() {
        for (String table : List.of(tables.userAccountDetailsTableName(), tables.administratorAuditEventsTableName(),
                tables.userStudyPlanDetailsTableName())) {
            try {
                // Missing sentinel is fine; the table must answer. Projection avoids reading any application contents.
                if (client.getItem(GetItemRequest.builder().tableName(table).key(PROBE_KEY)
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(2))
                                .apiCallAttemptTimeout(Duration.ofSeconds(1))).consistentRead(true)
                        .projectionExpression("pk").build()) == null) {
                    throw new IllegalStateException("Missing DynamoDB response");
                }
            } catch (RuntimeException failure) { return failure(table, failure); }
        }
        try {
            long current = versions.currentVersion(); // Fourth bounded read also probes the protected table.
            return current < requirement.minimumVersion()
                    ? new HealthDiagnosis("DATA_VERSION_BEHIND", requirement.behindMessage(current)) : null;
        } catch (RuntimeException failure) { return failure(tables.protectedOwnerIdentityTableName(), failure); }
    }

    private static HealthDiagnosis failure(String table, RuntimeException failure) {
        String code = failure instanceof DynamoDbException aws && aws.awsErrorDetails() != null
                ? aws.awsErrorDetails().errorCode() : null;
        if (failure instanceof ResourceNotFoundException || "ResourceNotFoundException".equals(code)) {
            return new HealthDiagnosis("TABLE_MISSING", "DynamoDB table " + table + " is missing");
        }
        if ("AccessDenied".equals(code) || "AccessDeniedException".equals(code)
                || failure instanceof DynamoDbException aws && aws.statusCode() == 403) {
            return new HealthDiagnosis("ACCESS_DENIED", "GetItem denied for DynamoDB table " + table);
        }
        // Error codes and class names may originate outside our code; do not echo arbitrary strings or messages.
        String safeCode = code != null && SAFE_CODES.contains(code) ? code : "UNCLASSIFIED";
        String type = failure.getClass().getSimpleName();
        if (!type.matches("[A-Za-z][A-Za-z0-9]{0,79}")) type = "RuntimeException";
        return new HealthDiagnosis("STORE_UNAVAILABLE", "GetItem unavailable for DynamoDB table " + table
                + " (" + type + ", " + safeCode + ")");
    }
}
