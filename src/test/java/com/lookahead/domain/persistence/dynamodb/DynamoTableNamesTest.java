package com.lookahead.domain.persistence.dynamodb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DynamoTableNamesTest {
    static DynamoTableNames names(String mode) { return DynamoTableNames.from(environment(mode)); }

    private static MockEnvironment environment(String mode) {
        return new MockEnvironment().withProperty("app.deployment-environment", mode)
                .withProperty("app.dynamodb.user-account-details-table-name", "lookahead-" + mode + "-user-account-details")
                .withProperty("app.dynamodb.administrator-audit-events-table-name", "lookahead-" + mode + "-administrator-audit-events")
                .withProperty("app.dynamodb.user-study-plan-details-table-name", "lookahead-" + mode + "-user-study-plan-details")
                .withProperty("app.dynamodb.protected-owner-identity-table-name", "lookahead-" + mode + "-protected-owner-identity");
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void approvedNamesAreLiteralAndExplicit(String mode) {
        var result = names(mode);
        assertThat(result.userAccountDetailsTableName()).isEqualTo("lookahead-" + mode + "-user-account-details");
        assertThat(result.administratorAuditEventsTableName()).isEqualTo("lookahead-" + mode + "-administrator-audit-events");
        assertThat(result.userStudyPlanDetailsTableName()).isEqualTo("lookahead-" + mode + "-user-study-plan-details");
        assertThat(result.protectedOwnerIdentityTableName()).isEqualTo("lookahead-" + mode + "-protected-owner-identity");
        for (String property : new String[]{"user-account-details", "administrator-audit-events",
                "user-study-plan-details", "protected-owner-identity"}) {
            for (String invalid : new String[]{"", "lookahead-dev-application-records", "table\";DELETE", "lookahead-dev-user-account-details "}) {
                assertThatIllegalArgumentException().isThrownBy(() -> DynamoTableNames.from(environment(mode)
                        .withProperty("app.dynamodb." + property + "-table-name", invalid)));
            }
        }
    }

    @Test void missingDuplicateCrossEnvironmentAndWrongDeploymentFailClosed() {
        assertThatIllegalArgumentException().isThrownBy(() -> DynamoTableNames.from(new MockEnvironment()
                .withProperty("app.deployment-environment", "dev")));
        assertThatIllegalArgumentException().isThrownBy(() -> DynamoTableNames.from(environment("dev")
                .withProperty("app.dynamodb.administrator-audit-events-table-name", "lookahead-dev-user-account-details")));
        assertThatIllegalArgumentException().isThrownBy(() -> DynamoTableNames.from(environment("dev")
                .withProperty("app.dynamodb.protected-owner-identity-table-name", "lookahead-prod-protected-owner-identity")));
        assertThatIllegalArgumentException().isThrownBy(() -> DynamoTableNames.from(environment("dev")
                .withProperty("app.deployment-environment", "prod")));
    }

    @ParameterizedTest @ValueSource(strings = {"local", "", "test", "production"})
    void noImplicitTargetOrLocalCloudFallback(String mode) {
        assertThatIllegalArgumentException().isThrownBy(() -> DynamoTableNames.from(environment(mode)));
    }
}
