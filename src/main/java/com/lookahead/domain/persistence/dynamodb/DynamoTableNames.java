package com.lookahead.domain.persistence.dynamodb;

import java.util.List;
import org.springframework.core.env.Environment;

/** Explicit names only. This class neither provisions tables nor selects a persistence backend. */
public record DynamoTableNames(String userAccountDetailsTableName,
        String administratorAuditEventsTableName, String userStudyPlanDetailsTableName,
        String protectedOwnerIdentityTableName) {
    private static final List<String> SUFFIXES = List.of("user-account-details",
            "administrator-audit-events", "user-study-plan-details", "protected-owner-identity");

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
}
