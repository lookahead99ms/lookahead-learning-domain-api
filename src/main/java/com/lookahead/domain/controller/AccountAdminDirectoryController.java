package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountAdminDirectoryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountAdminDirectoryController {
  private final AccountAdminDirectoryService directory;

  public AccountAdminDirectoryController(AccountAdminDirectoryService directory) {
    this.directory = directory;
  }

  @GetMapping("/api/v1/admin/accounts")
  public ApiResponse<?> list(
      @AuthenticationPrincipal AccountPrincipal actor,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "0") int page,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.success(directory.list(actor, query, page));
  }
}
