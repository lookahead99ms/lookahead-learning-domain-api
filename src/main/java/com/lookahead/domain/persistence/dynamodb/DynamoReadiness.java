package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.HealthReport;
import com.lookahead.domain.HealthReport.Diagnosis;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * The {@code dynamodb} readiness indicator, registered by {@link DynamoPersistenceConfiguration} in dynamodb mode:
 * four bounded strong GetItem probes (one per table) and the data-version watermark against the release's
 * requirement. It never writes, migrates or falls back to PostgreSQL; a store behind the catalog reports
 * DATA_VERSION_BEHIND until the migration task has run.
 */
public final class DynamoReadiness implements HealthIndicator {
    private static final Set<String> SAFE_CODES = Set.of("AccessDenied", "AccessDeniedException",
            "ResourceNotFoundException", "ProvisionedThroughputExceededException", "RequestLimitExceeded",
            "ThrottlingException", "InternalServerError", "ServiceUnavailable", "UnrecognizedClientException",
            "ExpiredTokenException", "ValidationException");
    private static final Map<String, AttributeValue> PROBE_KEY = Map.of(
            "pk", AttributeValue.builder().s("SYSTEM#READINESS").build(),
            "sk", AttributeValue.builder().s("PROBE").build());
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final DynamoDataVersionReader versions;
    private final DynamoDataRequirement requirement;
    private final HealthReport report;

    public DynamoReadiness(DynamoDbClient client, DynamoTableNames tables,
            DynamoDataRequirement requirement, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.versions = new DynamoDataVersionReader(client, tables);
        this.report = new HealthReport("dynamodb", "DynamoDB tables answer and the release data requirement is met",
                Objects.requireNonNull(clock, "clock"));
    }

    @Override public Health health() {
        Diagnosis down = diagnose();
        report.report(down);
        return down == null ? Health.up().build() : Health.down().build();
    }

    private Diagnosis diagnose() {
        for (String table : List.of(tables.userAccountDetailsTableName(), tables.administratorAuditEventsTableName(),
                tables.userStudyPlanDetailsTableName())) {
            try {
                // Missing sentinel is fine; the table must answer. Projection avoids reading any application contents.
                if (client.getItem(GetItemRequest.builder().tableName(table).key(PROBE_KEY)
                        .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(2))
                                .apiCallAttemptTimeout(java.time.Duration.ofSeconds(1))).consistentRead(true)
                        .projectionExpression("pk").build()) == null) {
                    throw new IllegalStateException("Missing DynamoDB response");
                }
            } catch (RuntimeException failure) { return failure(table, failure); }
        }
        try {
            long current = versions.currentVersion(); // Fourth bounded read also probes the protected table.
            return current < requirement.minimumVersion()
                    ? new Diagnosis("DATA_VERSION_BEHIND", requirement.behindMessage(current)) : null;
        } catch (RuntimeException failure) { return failure(tables.protectedOwnerIdentityTableName(), failure); }
    }

    private static Diagnosis failure(String table, RuntimeException failure) {
        String code = failure instanceof DynamoDbException aws && aws.awsErrorDetails() != null
                ? aws.awsErrorDetails().errorCode() : null;
        if (failure instanceof ResourceNotFoundException || "ResourceNotFoundException".equals(code)) {
            return new Diagnosis("TABLE_MISSING", "DynamoDB table " + table + " is missing");
        }
        if ("AccessDenied".equals(code) || "AccessDeniedException".equals(code)
                || failure instanceof DynamoDbException aws && aws.statusCode() == 403) {
            return new Diagnosis("ACCESS_DENIED", "GetItem denied for DynamoDB table " + table);
        }
        // Error codes and class names may originate outside our code; do not echo arbitrary strings or messages.
        String safeCode = code != null && SAFE_CODES.contains(code) ? code : "UNCLASSIFIED";
        String type = failure.getClass().getSimpleName();
        if (!type.matches("[A-Za-z][A-Za-z0-9]{0,79}")) type = "RuntimeException";
        return new Diagnosis("STORE_UNAVAILABLE", "GetItem unavailable for DynamoDB table " + table
                + " (" + type + ", " + safeCode + ")");
    }
}
