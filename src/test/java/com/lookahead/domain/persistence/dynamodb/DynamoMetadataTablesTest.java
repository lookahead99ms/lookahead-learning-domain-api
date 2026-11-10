package com.lookahead.domain.persistence.dynamodb;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DynamoMetadataTablesTest {
    static DynamoMetadataTables names(String mode) {return DynamoMetadataTables.from(environment(mode));}
    private static MockEnvironment environment(String mode) {
        return new MockEnvironment().withProperty("app.deployment-environment",mode)
                .withProperty("app.dynamodb.user-account-details-table-name","lookahead-"+mode+"-user-account-details")
                .withProperty("app.dynamodb.administrator-audit-events-table-name","lookahead-"+mode+"-administrator-audit-events")
                .withProperty("app.dynamodb.protected-owner-identity-table-name","lookahead-"+mode+"-protected-owner-identity");
    }
    @Test void bothEnvironmentsNeedExactlyThreeNamedTables() {
        for(String mode:new String[]{"dev","prod"}) {
            assertThat(names(mode)).isEqualTo(DynamoMetadataTables.legacy(DynamoTableNamesTest.names(mode)));
            for(String field:new String[]{"user-account-details","administrator-audit-events","protected-owner-identity"})
                for(String invalid:new String[]{"", "table\";DELETE", "lookahead-"+mode+"-user-study-plan-details"})
                    assertThatIllegalArgumentException().isThrownBy(()->DynamoMetadataTables.from(environment(mode)
                            .withProperty("app.dynamodb."+field+"-table-name",invalid)));
        }
    }
    @Test void missingMixedAndImplicitTargetsFailClosed() {
        assertThatIllegalArgumentException().isThrownBy(()->DynamoMetadataTables.from(new MockEnvironment()));
        assertThatIllegalArgumentException().isThrownBy(()->DynamoMetadataTables.from(new MockEnvironment().withProperty("app.deployment-environment","prod")));
        assertThatIllegalArgumentException().isThrownBy(()->DynamoMetadataTables.from(environment("dev").withProperty("app.deployment-environment","prod")));
        assertThatIllegalArgumentException().isThrownBy(()->DynamoMetadataTables.from(environment("dev").withProperty("app.dynamodb.protected-owner-identity-table-name","lookahead-prod-protected-owner-identity")));
        assertThatIllegalArgumentException().isThrownBy(()->DynamoMetadataTables.from(environment("local")));
    }
}
