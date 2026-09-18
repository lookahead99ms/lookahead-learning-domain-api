package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.PlanService;
import com.lookahead.learning.content.util.PayloadReaders;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.UUID;

@RestController
@Profile("accounts")
@RequestMapping("/api/v1")
public class PlanController {
    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/account-catalog")
    ResponseEntity<?> catalog() {
        return ok(planService.catalogMetadata());
    }

    @GetMapping("/plans")
    ResponseEntity<?> list(@AuthenticationPrincipal AccountPrincipal user,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String after) {
        return ok(planService.list(user.accountId(), limit, after));
    }

    @GetMapping("/plans/{id}")
    ResponseEntity<?> get(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id) {
        return ok(planService.get(user.accountId(), PayloadReaders.uuid(id), null));
    }

    @GetMapping("/plans/{id}/versions/{version}")
    ResponseEntity<?> version(@AuthenticationPrincipal AccountPrincipal user,
            @PathVariable String id, @PathVariable String version) {
        return ok(planService.get(user.accountId(), PayloadReaders.uuid(id), PayloadReaders.uuid(version)));
    }

    @PostMapping("/plans")
    ResponseEntity<?> create(@AuthenticationPrincipal AccountPrincipal user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return mutation(planService.create(user.accountId(), key(key), body, false));
    }

    @PostMapping("/plans/imports")
    ResponseEntity<?> imports(@AuthenticationPrincipal AccountPrincipal user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return mutation(planService.create(user.accountId(), key(key), body, true));
    }

    @PostMapping("/plans/{id}/activity")
    ResponseEntity<?> activity(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return mutation(planService.activity(user.accountId(), PayloadReaders.uuid(id), key(key), body));
    }

    @PostMapping("/plans/{id}/versions")
    ResponseEntity<?> versions(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return mutation(planService.saveVersion(user.accountId(), PayloadReaders.uuid(id), key(key), body));
    }

    @DeleteMapping("/plans/{id}")
    ResponseEntity<?> delete(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String expected) {
        if (expected == null) {
            throw new AccountFailure(428, "REVISION_REQUIRED", "If-Match revision is required");
        }
        if (!expected.matches("\"revision-[1-9][0-9]*\"")) {
            throw new AccountFailure(400, "INVALID_REVISION", "Use If-Match: \"revision-N\"");
        }
        long revision = Long.parseLong(expected.substring(10, expected.length() - 1));
        return mutation(planService.delete(user.accountId(), PayloadReaders.uuid(id), key(key), revision));
    }

    private UUID key(String value) {
        if (value == null) {
            throw new AccountFailure(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        }
        return PayloadReaders.uuid(value);
    }

    private ResponseEntity<?> ok(JsonNode data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(data));
    }

    private ResponseEntity<?> mutation(PlanService.Mutation result) {
        if (result.status() == 204) {
            return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
        }
        var response = ResponseEntity.status(result.status()).header("Cache-Control", "no-store");
        if (result.status() == 201) {
            response.location(URI.create("/api/v1/plans/" + result.data().path("planId").asText()));
        }
        return response.body(ApiResponse.success(result.data()));
    }
}
