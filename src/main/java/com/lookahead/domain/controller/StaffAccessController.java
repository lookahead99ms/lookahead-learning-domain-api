package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountDirectoryService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.access.StaffAliasService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class StaffAccessController {
  private final StaffAccessService access;
  private final AccountDirectoryService directory;
  private final StaffAliasService aliases;

  public StaffAccessController(
      StaffAccessService access, AccountDirectoryService directory, StaffAliasService aliases) {
    this.access = access;
    this.directory = directory;
    this.aliases = aliases;
  }

  @GetMapping("/api/v1/account/permissions")
  public ApiResponse<?> permissions(
      @AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(access.permissions(actor));
  }

  @GetMapping("/api/v1/admin/access")
  public ApiResponse<?> list(
      @AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(aliases.assignments(actor));
  }

  @GetMapping("/api/v1/admin/access/catalog")
  public ApiResponse<?> catalog(
      @AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(access.catalog(actor));
  }

  /** Owner only since DLV-969: finding a person by email or username reveals who they are. */
  @PostMapping("/api/v1/admin/access/lookup")
  public ApiResponse<?> lookup(
      @AuthenticationPrincipal AccountPrincipal actor,
      @RequestBody Map<String, String> request,
      HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(directory.lookup(actor, request.get("query")));
  }

  @PostMapping("/api/v1/admin/access")
  public ApiResponse<?> save(
      @AuthenticationPrincipal AccountPrincipal actor,
      @RequestBody StaffAccessChange request,
      HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(access.save(actor, request));
  }

  private static void noStore(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }
}
