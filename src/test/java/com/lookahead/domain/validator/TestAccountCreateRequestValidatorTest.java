package com.lookahead.domain.validator;

import com.lookahead.domain.dto.MembershipPurchaseDecision;
import com.lookahead.domain.dto.TestAccountCreateKind;
import com.lookahead.domain.dto.TestAccountCreate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** DLV-1016: which operation a test-account body asks for, moved here from TestAccountController; the first test that holds decides. */
class TestAccountCreateRequestValidatorTest {
    final TestAccountCreateRequestValidator shapes = new TestAccountCreateRequestValidator();
    final MembershipPurchaseDecision decision = new MembershipPurchaseDecision(UUID.randomUUID(), "approve");

    @Test void aPurchaseDecisionComesBeforeARosterRestoreBeforeTheNextRosterAccounts() {
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, true, true, "RESTORE", decision))).isEqualTo(TestAccountCreateKind.PURCHASE_DECISION);
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, null, null, null, decision))).isEqualTo(TestAccountCreateKind.PURCHASE_DECISION);
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, true, true, "RESTORE", null))).isEqualTo(TestAccountCreateKind.RESTORE_ROSTER);
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, null, true, null, null))).isEqualTo(TestAccountCreateKind.RESTORE_ROSTER);
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, true, false, null, null))).isEqualTo(TestAccountCreateKind.NEXT_ROSTER);
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, true, null, null, null))).isEqualTo(TestAccountCreateKind.NEXT_ROSTER);
    }
    @Test void anythingElseIncludingNoBodyCreatesOneAccount() {
        assertThat(shapes.kind(null)).isEqualTo(TestAccountCreateKind.ACCOUNT);
        assertThat(shapes.kind(new TestAccountCreate(null, null, null, null, null, null, null))).isEqualTo(TestAccountCreateKind.ACCOUNT);
        assertThat(shapes.kind(new TestAccountCreate(UUID.randomUUID(), "password", "scenario", false, false, "RESTORE", null))).isEqualTo(TestAccountCreateKind.ACCOUNT);
    }
}
