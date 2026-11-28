package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.dto.TestSessionStart;
import com.lookahead.domain.security.filter.TestSessionFilter;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;

/**
 * DLV-969 S35: what the Gateway asks before and when a test session starts (DEV only; the Gateway
 * secret guards both).
 */
@RestController
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev'")
public class TestSessionController {
  private final TestSessionService sessions;

  public TestSessionController(TestSessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/internal/v1/test-sessions/accounts")
  public ApiResponse<?> accounts(
      @RequestHeader(value = TestSessionFilter.HOLDER_HEADER, required = false) String holder,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.success(sessions.usable(holder));
  }

  @PostMapping("/internal/v1/test-sessions/start")
  public ApiResponse<?> start(
      @RequestHeader(value = TestSessionFilter.HOLDER_HEADER, required = false) String holder,
      @RequestBody(required = false) TestSessionStart request,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    // actAs refuses a missing account id (422); the holder and the account are checked in the
    // database.
    var principal = sessions.start(holder, request == null ? null : request.accountId());
    return ApiResponse.success(
        Map.of(
            "accountId",
            principal.accountId(),
            "username",
            principal.username(),
            "displayName",
            principal.displayName()));
  }
}
