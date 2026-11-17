package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.*;

class DynamoMigrationCatalogTest {
    record Step(int version, String name, String definition) implements DynamoMigrationStep {
        @Override public void apply(DynamoDbClient client, DynamoTableNames tables) { }
        @Override public void verify(DynamoDbClient client, DynamoTableNames tables) { }
    }

    @Test void stepsAreNumberedFromOneWithoutGapsAndNamedLikeRequirements() {
        var catalog = new DynamoMigrationCatalog(List.of(new Step(1, "base-tables", "tables"), new Step(2, "plan-index", "index")));
        assertThat(catalog.latestVersion()).isEqualTo(2);
        assertThat(catalog.step(2).name()).isEqualTo("plan-index");
        assertThat(catalog.requirement().minimumVersion()).isEqualTo(2);
        assertThat(catalog.requirement().stepNames()).containsExactly("base-tables", "plan-index");
        assertThatThrownBy(() -> catalog.step(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.step(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoMigrationCatalog(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoMigrationCatalog(List.of(new Step(2, "a", "x")))).hasMessageContaining("consecutively");
        assertThatThrownBy(() -> new DynamoMigrationCatalog(List.of(new Step(1, "a", "x"), new Step(3, "b", "y")))).hasMessageContaining("consecutively");
        assertThatThrownBy(() -> new DynamoMigrationCatalog(List.of(new Step(1, "a", "x"), new Step(2, "a", "y")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoMigrationCatalog(List.of(new Step(1, "Bad Name", "x")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoMigrationCatalog(List.of(new Step(1, "a", " ")))).hasMessageContaining("definition");
    }

    @Test void checksumBindsVersionNameAndDefinition() {
        var one = new DynamoMigrationCatalog(List.of(new Step(1, "base-tables", "tables")));
        assertThat(one.checksum(1)).matches("[0-9a-f]{64}")
                .isEqualTo(new DynamoMigrationCatalog(List.of(new Step(1, "base-tables", "tables"))).checksum(1));
        assertThat(new DynamoMigrationCatalog(List.of(new Step(1, "base-tables", "tables changed"))).checksum(1)).isNotEqualTo(one.checksum(1));
        assertThat(new DynamoMigrationCatalog(List.of(new Step(1, "base-table", "tables"))).checksum(1)).isNotEqualTo(one.checksum(1));
    }

    @Test void theDomainCatalogIsBoundToTheReleaseRequirement() {
        var catalog = DomainDynamoCatalog.catalog();
        assertThat(catalog.latestVersion()).isEqualTo(DomainDynamoCatalog.REQUIRED);
        assertThat(catalog.requirement().stepNames()).containsExactly("base-tables", "staff-alias-counters", "membership-catalog", "review-record-counters", "account-access-import");
        // Applied definitions are immutable: a changed checksum here means a numbered step was edited, which the ledger refuses.
        assertThat(catalog.checksum(1)).matches("[0-9a-f]{64}");
        assertThat(catalog.checksum(2)).matches("[0-9a-f]{64}").isNotEqualTo(catalog.checksum(1));
        assertThat(catalog.step(2).definition()).contains(DomainDynamoCatalog.StaffAliasCounters.INITIALIZATION.toString())
                .contains(DomainDynamoCatalog.StaffAliasCounters.INITIALIZED_AT.toString());
        assertThat(catalog.checksum(3)).matches("[0-9a-f]{64}").isNotEqualTo(catalog.checksum(2));
        assertThat(catalog.checksum(4)).matches("[0-9a-f]{64}").isNotEqualTo(catalog.checksum(3));
        // Step 3 names every seeded plan and its fixed seed time; step 4 names both counters.
        assertThat(catalog.step(3).definition()).contains("free, learn, grow, advance, complete, course-pass, interview-sprint, monthly-promo, annual-promo, interview-pass-promo")
                .contains(DomainDynamoCatalog.MembershipCatalog.SEEDED_AT.toString()).contains("CATALOG / META");
        assertThat(catalog.step(4).definition()).contains("SEQUENCE#review-record").contains("SEQUENCE#support-request").contains("lastAssigned 0");
        assertThat(com.lookahead.domain.membership.MembershipCatalogSeed.PLANS).hasSize(10);
        // Step 5 names its source, what it writes, the counters it raises and what it refuses; its definition is as immutable as the others.
        assertThat(catalog.checksum(5)).matches("[0-9a-f]{64}").isNotEqualTo(catalog.checksum(4));
        assertThat(catalog.step(5).definition()).contains("test_account_registry").contains("importedFrom postgresql").contains("IMPORT#account-access / RESULT")
                .contains("SEQUENCE#review-record").contains("expire event").contains("admitted account unknown to the import is refused").contains("imports nothing");
    }
}
