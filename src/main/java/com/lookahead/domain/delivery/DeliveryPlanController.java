package com.lookahead.domain.delivery;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * DLV-971: /api/v1/delivery/plan. The Gateway forwards these with the session, CSRF check and bearer token (SCOPE_account),
 * as it does for /api/v1/platform-reviews. Bodies carry {@code expectedVersion} (the plan version the page loaded) and the
 * item; a stale version answers 409 VERSION_CONFLICT with {@code currentRevision}, an unknown ticket 404.
 */
@RestController
@Profile("accounts")
@RequestMapping("/api/v1/delivery/plan")
public class DeliveryPlanController {
    private final DeliveryPlanService plans;

    public DeliveryPlanController(DeliveryPlanService plans) { this.plans = plans; }

    @GetMapping
    public ApiResponse<JsonNode> plan(@AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
        noStore(response); return ApiResponse.success(plans.plan(actor));
    }

    @PostMapping(value = "/work-items", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DeliveryPlanService.Created>> create(@AuthenticationPrincipal AccountPrincipal actor, @RequestBody JsonNode body) {
        var created = plans.create(actor, expectedVersion(body), body.path("item"));
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(ApiResponse.success(created));
    }

    @PutMapping(value = "/work-items/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DeliveryPlanService.Created> update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
                                                           @RequestBody JsonNode body, HttpServletResponse response) {
        noStore(response); return ApiResponse.success(plans.update(actor, id, expectedVersion(body), body.path("item")));
    }

    @PostMapping(value = "/work-items/{id}/resolve-conflict", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DeliveryPlanService.Created> resolve(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable String id,
                                                            @RequestBody JsonNode body, HttpServletResponse response) {
        if (body == null || !Set.of("expectedVersion", "keep").equals(body.propertyNames()) || !body.path("keep").isString())
            throw DeliveryPlanService.invalid("Choose dev or local.");
        noStore(response); return ApiResponse.success(plans.resolveConflict(actor, id, expectedVersion(body), body.path("keep").asString()));
    }

    private static Long expectedVersion(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("expectedVersion")) throw DeliveryPlanService.invalid("Send the plan version you loaded as expectedVersion.");
        if (!body.path("expectedVersion").isIntegralNumber()) throw DeliveryPlanService.invalid("expectedVersion must be the plan's version number.");
        return body.path("expectedVersion").asLong();
    }

    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
