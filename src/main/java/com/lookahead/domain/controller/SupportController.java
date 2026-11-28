package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.dto.SupportFeedback;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.review.SupportFeedbackService;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("accounts")
@RequestMapping("/api/v1/support")
public class SupportController {
  private final SupportFeedbackService support;

  public SupportController(SupportFeedbackService support) {
    this.support = support;
  }

  @PostMapping
  public ResponseEntity<?> submit(
      @AuthenticationPrincipal AccountPrincipal principal,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody SupportFeedback feedback) {
    var receipt = support.submit(principal, key, feedback);
    // DLV-971: "recorded" (kept for Team Reviews, mail not configured) is accepted like a sent
    // email; "unconfirmed" stays 503.
    return ResponseEntity.status(
            Set.of("accepted", "recorded").contains(receipt.status()) ? 202 : 503)
        .header("Cache-Control", "no-store")
        .body(ApiResponse.success(receipt));
  }
}
