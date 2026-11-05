package com.lookahead.domain.testaccounts;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLV-969 (2026-11-04): every action on {@code POST /api/v1/admin/test-accounts} sends only its own fields. With required
 * primitive flags the JSON reader refused every body ("Request body is not valid JSON") in DEV: Restore, purchase decisions
 * and even Create test account. These are the exact bodies the Web sends, read with the default JSON settings.
 */
class CreateRequestJsonTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ID = "11111111-2222-4333-8444-555555555555";

    private CreatedTestAccounts.Create read(String body) { return json.readValue(body, CreatedTestAccounts.Create.class); }

    @Test void createWithOnlyAKeyAndAScenario() {
        var create = read("{\"key\":\"" + ID + "\",\"scenario\":\"learner03\"}");
        assertThat(create.key()).isEqualTo(UUID.fromString(ID));
        assertThat(create.scenario()).isEqualTo("learner03");
        assertThat(create.createsRoster()).isFalse();
        assertThat(create.restoresRoster()).isFalse();
        assertThat(create.purchaseDecision()).isNull();
    }

    @Test void createTheRosterRestoreItAndDecideAPurchaseEachWithTheirOwnFieldsOnly() {
        assertThat(read("{\"roster\":true}").createsRoster()).isTrue();
        var restore = read("{\"restoreRoster\":true,\"confirm\":\"RESTORE\"}");
        assertThat(restore.restoresRoster()).isTrue();
        assertThat(restore.createsRoster()).isFalse();
        assertThat(restore.confirm()).isEqualTo("RESTORE");
        var decision = read("{\"purchaseDecision\":{\"purchaseId\":\"" + ID + "\",\"decision\":\"approve\"}}").purchaseDecision();
        assertThat(decision.purchaseId()).isEqualTo(UUID.fromString(ID));
        assertThat(decision.decision()).isEqualTo("approve");
        assertThat(read("{\"roster\":false,\"restoreRoster\":null}").createsRoster()).isFalse();
    }
}
