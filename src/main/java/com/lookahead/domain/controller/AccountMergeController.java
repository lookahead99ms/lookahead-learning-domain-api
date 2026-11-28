package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.signin.AccountMergeService;
import com.lookahead.domain.validator.AccountMergeRequestValidator;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * DLV-969 S36.6: the person's own merge of accounts that share their verified email (an account
 * route, not admin). The request always answers 202 with nothing in it; the link answers 404 for
 * anything but a live link of one of the caller's accounts.
 */
@RestController
@ConditionalOnExpression(
    "'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
@RequestMapping("/api/v1/account/merge")
public class AccountMergeController {
  private final AccountMergeService merges;
  private final AccountMergeRequestValidator shapes;

  public AccountMergeController(AccountMergeService merges, AccountMergeRequestValidator shapes) {
    this.merges = merges;
    this.shapes = shapes;
  }

  @ModelAttribute
  void noCache(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/request")
  public ResponseEntity<ApiResponse<?>> request(
      @AuthenticationPrincipal AccountPrincipal actor,
      @RequestBody(required = false) Object ignored) {
    merges.request(actor);
    return ResponseEntity.accepted().body(ApiResponse.success(Map.of()));
  }

  @GetMapping("/{token}")
  public ApiResponse<?> candidates(
      @AuthenticationPrincipal AccountPrincipal actor, @PathVariable String token) {
    return ApiResponse.success(merges.candidates(actor, token));
  }

  @PostMapping("/{token}")
  public ApiResponse<?> merge(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable String token,
      @RequestBody(required = false) Object payload) {
    return ApiResponse.success(merges.merge(actor, token, shapes.keepAccountId(payload)));
  }
}
