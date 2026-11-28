package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.delivery.DeliveryPlanService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * DLV-971: /api/v1/delivery/plan. The Gateway forwards these with the session, CSRF check and
 * bearer token (SCOPE_account), as it does for /api/v1/platform-reviews. Bodies carry {@code
 * expectedVersion} (the plan version the page loaded) and the item; a stale version answers 409
 * VERSION_CONFLICT with {@code currentRevision}, an unknown ticket 404. {@code PUT /inbox} (the
 * plan file as the body) and {@code POST /inbox/sync} are the Delivery Plan page's upload and Sync
 * plan inbox (DLV-971, 2026-11-07); Owner decision 2026-11-07: every write here (tickets,
 * conflicts, the inbox) is DEV and local only ({@code DevOnly}, 404 {@code DEV_ONLY} in prod); PROD
 * reads the plan only.
 */
@RestController
@Profile("accounts")
@RequestMapping("/api/v1/delivery/plan")
public class DeliveryPlanController {
  private final DeliveryPlanService plans;

  public DeliveryPlanController(DeliveryPlanService plans) {
    this.plans = plans;
  }

  @GetMapping
  public ApiResponse<JsonNode> plan(
      @AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(plans.plan(actor));
  }

  // DLV-1016: the DEV-only gate (404 DEV_ONLY in prod) and the checks on the body are the first
  // things each service
  // method below does, in that order; this class called them itself before.

  @PostMapping(value = "/work-items", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<DeliveryPlanService.Created>> create(
      @AuthenticationPrincipal AccountPrincipal actor, @RequestBody JsonNode body) {
    var created = plans.create(actor, body);
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(ApiResponse.success(created));
  }

  @PutMapping(value = "/work-items/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<DeliveryPlanService.Created> update(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable String id,
      @RequestBody JsonNode body,
      HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(plans.update(actor, id, body));
  }

  @PostMapping(
      value = "/work-items/{id}/resolve-conflict",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<DeliveryPlanService.Created> resolve(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable String id,
      @RequestBody JsonNode body,
      HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(plans.resolveConflict(actor, id, body));
  }

  /**
   * DLV-971 (owner, 2026-11-07): the local plan file as the body; stored as the inbox and merged at
   * once (owner or co-author).
   */
  @PutMapping(value = "/inbox", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<DeliveryPlanService.Sync> uploadInbox(
      @AuthenticationPrincipal AccountPrincipal actor,
      @RequestBody byte[] body,
      HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(plans.uploadInbox(actor, body));
  }

  /**
   * Sync plan inbox on the Delivery Plan page: merges a file already placed in delivery/inbox/
   * (owner or co-author).
   */
  @PostMapping("/inbox/sync")
  public ApiResponse<DeliveryPlanService.Sync> syncInbox(
      @AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(plans.syncInboxNow(actor));
  }

  private static void noStore(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }
}
