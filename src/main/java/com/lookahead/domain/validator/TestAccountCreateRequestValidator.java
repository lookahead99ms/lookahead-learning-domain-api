package com.lookahead.domain.validator;

import com.lookahead.domain.dto.TestAccountCreate;
import com.lookahead.domain.dto.TestAccountCreateKind;
import org.springframework.stereotype.Component;

/**
 * Which of the four operations a body sent to {@code POST /api/v1/admin/test-accounts} asks for.
 * DLV-1016: the three tests and their order (a purchase decision before a roster restore before the
 * next roster accounts) moved word for word from TestAccountController; the first that holds
 * decides.
 */
@Component
public class TestAccountCreateRequestValidator {
  public TestAccountCreateKind kind(TestAccountCreate request) {
    // DLV-969 (owner, 2026-11-04): {"purchaseDecision": {"purchaseId": "<uuid>", "decision":
    // "approve"|"decline"}}
    // is the owner's or an admin's decision on a DEV purchase request.
    if (request != null && request.purchaseDecision() != null)
      return TestAccountCreateKind.PURCHASE_DECISION;
    // DLV-969 (owner, 2026-11-04): {"restoreRoster": true, "confirm": "RESTORE"} brings back
    // deleted DEV roster accounts.
    if (request != null && request.restoresRoster()) return TestAccountCreateKind.RESTORE_ROSTER;
    if (request != null && request.createsRoster()) return TestAccountCreateKind.NEXT_ROSTER;
    return TestAccountCreateKind.ACCOUNT;
  }
}
