package com.lookahead.domain.persistence.dynamodb;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Explicit names only. This class neither provisions tables nor selects a persistence backend.
 * DLV-970: a Local process (DynamoDB Local on the laptop) reuses the four DEV literal names, so the name
 * contract stays closed; nothing else about Local is shared with DEV.
 */
public record DynamoTableNames(String userAccountDetailsTableName,
        String administratorAuditEventsTableName, String userStudyPlanDetailsTableName,
        String protectedOwnerIdentityTableName) {
    private static final List<String> SUFFIXES = List.of("user-account-details",
            "administrator-audit-events", "user-study-plan-details", "protected-owner-identity");
    /** The process variables the migration entrypoint and the task definition use, in {@link #SUFFIXES} order. */
    static final List<String> VARIABLES = List.of("LOOKAHEAD_DYNAMODB_USER_ACCOUNT_DETAILS_TABLE",
            "LOOKAHEAD_DYNAMODB_ADMINISTRATOR_AUDIT_EVENTS_TABLE", "LOOKAHEAD_DYNAMODB_USER_STUDY_PLAN_DETAILS_TABLE",
            "LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE");

    public DynamoTableNames {
        var names = new String[]{userAccountDetailsTableName, administratorAuditEventsTableName,
                userStudyPlanDetailsTableName, protectedOwnerIdentityTableName};
        String prefix = null;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name == null || !(name.equals("lookahead-dev-" + SUFFIXES.get(i))
                    || name.equals("lookahead-prod-" + SUFFIXES.get(i)))) {
                throw new IllegalArgumentException("Missing or unapproved DynamoDB table name");
            }
            String current = name.startsWith("lookahead-dev-") ? "dev" : "prod";
            if (prefix != null && !prefix.equals(current)) {
                throw new IllegalArgumentException("DynamoDB tables must belong to one environment");
            }
            prefix = current;
        }
    }

    public static DynamoTableNames from(Environment environment) {
        String mode = environment.getProperty("app.deployment-environment");
        if (!"dev".equals(mode) && !"prod".equals(mode)) {
            throw new IllegalArgumentException("DynamoDB requires an explicit dev or prod target");
        }
        var names = new DynamoTableNames(
                environment.getProperty("app.dynamodb.user-account-details-table-name"),
                environment.getProperty("app.dynamodb.administrator-audit-events-table-name"),
                environment.getProperty("app.dynamodb.user-study-plan-details-table-name"),
                environment.getProperty("app.dynamodb.protected-owner-identity-table-name"));
        if (!names.userAccountDetailsTableName.startsWith("lookahead-" + mode + "-")) {
            throw new IllegalArgumentException("DynamoDB table environment does not match deployment");
        }
        return names;
    }

    /** Local: the DEV literal names on the laptop's DynamoDB Local; dev/prod: the four explicit properties. */
    public static DynamoTableNames select(Environment environment) {
        return "local".equals(environment.getProperty("app.deployment-environment")) ? local() : from(environment);
    }

    /** The same selection from process variables, for the migration entrypoint. */
    public static DynamoTableNames fromVariables(String mode, Map<String, String> variables) {
        if ("local".equals(mode)) return local();
        if (!"dev".equals(mode) && !"prod".equals(mode)) throw new IllegalArgumentException("DynamoDB requires an explicit dev or prod target");
        var names = new DynamoTableNames(variables.get(VARIABLES.get(0)), variables.get(VARIABLES.get(1)),
                variables.get(VARIABLES.get(2)), variables.get(VARIABLES.get(3)));
        if (!names.userAccountDetailsTableName.startsWith("lookahead-" + mode + "-"))
            throw new IllegalArgumentException("DynamoDB table environment does not match deployment");
        return names;
    }

    public static DynamoTableNames local() {
        return new DynamoTableNames("lookahead-dev-" + SUFFIXES.get(0), "lookahead-dev-" + SUFFIXES.get(1),
                "lookahead-dev-" + SUFFIXES.get(2), "lookahead-dev-" + SUFFIXES.get(3));
    }

    public List<String> all() {
        return List.of(userAccountDetailsTableName, administratorAuditEventsTableName,
                userStudyPlanDetailsTableName, protectedOwnerIdentityTableName);
    }
}
