package com.lookahead.domain.persistence.dynamodb;

import java.util.List;
import org.springframework.core.env.Environment;

/** The approved hybrid uses three tables; payload documents live in private S3. */
public record DynamoMetadataTables(String userAccountDetailsTableName,
        String administratorAuditEventsTableName, String protectedOwnerIdentityTableName) {
    public DynamoMetadataTables {
        String[] names = {userAccountDetailsTableName, administratorAuditEventsTableName, protectedOwnerIdentityTableName};
        List<String> suffixes = List.of("user-account-details", "administrator-audit-events", "protected-owner-identity");
        String mode = null;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name == null || !(name.equals("lookahead-dev-" + suffixes.get(i)) || name.equals("lookahead-prod-" + suffixes.get(i))))
                throw new IllegalArgumentException("Missing or unapproved metadata table name");
            String current = name.startsWith("lookahead-dev-") ? "dev" : "prod";
            if (mode != null && !mode.equals(current)) throw new IllegalArgumentException("Metadata tables cross environments");
            mode = current;
        }
    }
    public static DynamoMetadataTables from(Environment environment) {
        String mode = environment.getProperty("app.deployment-environment");
        if (!"dev".equals(mode) && !"prod".equals(mode)) throw new IllegalArgumentException("Explicit dev/prod target required");
        var tables = new DynamoMetadataTables(environment.getProperty("app.dynamodb.user-account-details-table-name"),
                environment.getProperty("app.dynamodb.administrator-audit-events-table-name"),
                environment.getProperty("app.dynamodb.protected-owner-identity-table-name"));
        if (!tables.userAccountDetailsTableName.startsWith("lookahead-" + mode + "-"))
            throw new IllegalArgumentException("Metadata target does not match deployment");
        return tables;
    }
    static DynamoMetadataTables legacy(DynamoTableNames names) {
        return new DynamoMetadataTables(names.userAccountDetailsTableName(),names.administratorAuditEventsTableName(),names.protectedOwnerIdentityTableName());
    }
}
