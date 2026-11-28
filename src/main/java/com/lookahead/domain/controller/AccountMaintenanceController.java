package com.lookahead.domain.controller;

import com.lookahead.domain.dto.AccountMaintenanceConfirmation;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountMaintenanceService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountMaintenanceController {
  private final AccountMaintenanceService maintenance;

  public AccountMaintenanceController(AccountMaintenanceService maintenance) {
    this.maintenance = maintenance;
  }

  @ModelAttribute
  void noCache(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/api/v1/admin/accounts/{id}/downgrade")
  public ApiResponse<?> downgrade(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable UUID id,
      @RequestBody AccountMaintenanceConfirmation request) {
    maintenance.downgrade(actor, id, request);
    return ApiResponse.success(Map.of("downgraded", true));
  }

  @PostMapping("/api/v1/admin/accounts/{id}/delete")
  public ApiResponse<?> delete(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable UUID id,
      @RequestBody AccountMaintenanceConfirmation request) {
    maintenance.deleteLearner(actor, id, request);
    return ApiResponse.success(Map.of("deleted", true));
  }
}
