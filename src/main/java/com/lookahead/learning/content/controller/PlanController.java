package com.lookahead.learning.content.controller;

import com.lookahead.domain.Events;
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
        return logged("plan.create", user, null, () -> planService.create(user.accountId(), key(key), body, false), "Study plan {} saved by account {}");
    }

    @PostMapping("/plans/imports")
    ResponseEntity<?> imports(@AuthenticationPrincipal AccountPrincipal user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return logged("plan.import", user, null, () -> planService.create(user.accountId(), key(key), body, true), "Study plan {} imported by account {}");
    }

    @PostMapping("/plans/{id}/name")
    ResponseEntity<?> rename(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return logged("plan.rename", user, id, () -> planService.rename(user.accountId(), PayloadReaders.uuid(id), key(key), body), "Study plan {} renamed by account {}");
    }

    @PostMapping("/plans/{id}/activity")
    ResponseEntity<?> activity(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        // Progress is saved many times a session: only its refusals are logged.
        return logged("plan.activity", user, id, () -> planService.activity(user.accountId(), PayloadReaders.uuid(id), key(key), body), null);
    }

    @PostMapping("/plans/{id}/lifecycle")
    ResponseEntity<?> lifecycle(@AuthenticationPrincipal AccountPrincipal user,@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode body){
        String state = body == null ? "" : body.path("state").asText();
        String action = switch (state) { case "active" -> "plan.activate"; case "archived" -> "plan.archive"; case "completed" -> "plan.complete"; default -> "plan.lifecycle"; };
        String done = switch (state) { case "active" -> "activated"; case "archived", "completed" -> state; default -> "changed"; };
        return logged(action, user, id, () -> planService.lifecycle(user.accountId(),PayloadReaders.uuid(id),key(key),body),
                "Study plan {} " + done + " by account {}");
    }

    @PostMapping("/plans/{id}/versions")
    ResponseEntity<?> versions(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return logged("plan.version", user, id, () -> planService.saveVersion(user.accountId(), PayloadReaders.uuid(id), key(key), body), "Study plan {} saved as a new version by account {}");
    }

    @DeleteMapping("/plans/{id}")
    ResponseEntity<?> delete(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String expected) {
        return logged("plan.delete", user, id, () -> {
            if (expected == null) {
                throw new AccountFailure(428, "REVISION_REQUIRED", "If-Match revision is required");
            }
            if (!expected.matches("\"revision-[1-9][0-9]*\"")) {
                throw new AccountFailure(400, "INVALID_REVISION", "Use If-Match: \"revision-N\"");
            }
            long revision = Long.parseLong(expected.substring(10, expected.length() - 1));
            return planService.delete(user.accountId(), PayloadReaders.uuid(id), key(key), revision);
        }, "Study plan {} deleted by account {}");
    }

    /**
     * DLV-969: one line per plan change once it has committed (the service's transaction ends before this returns), or
     * one WARN line with the refusal code. Never the plan's goal, name or content. {@code success} null: refusals only.
     */
    private ResponseEntity<?> logged(String action, AccountPrincipal user, String pathId, java.util.function.Supplier<PlanService.Mutation> call, String success) {
        UUID account = user == null ? null : user.accountId();
        String planId = pathId != null && pathId.matches("[0-9a-f-]{36}") ? pathId : null;
        PlanService.Mutation result;
        try { result = call.get(); }
        catch (AccountFailure refused) { throw refusal(action, account, planId, refused.code(), refused); }
        catch (IllegalArgumentException invalid) { throw refusal(action, account, planId, "INVALID_ACCOUNT_PAYLOAD", invalid); }
        if (success != null) {
            String id = planId != null ? planId : result.data() == null ? null : result.data().path("planId").asText(null);
            Events.success(Events.EVENT, action).addKeyValue("account.id", account).addKeyValue("target.id", id)
                    .addKeyValue("http.status", result.status()).log(success, id, account);
        }
        return mutation(result);
    }

    private static RuntimeException refusal(String action, UUID account, String planId, String code, RuntimeException failure) {
        Events.refused(Events.EVENT, action, code).addKeyValue("account.id", account).addKeyValue("target.id", planId)
                .log("Study plan change {} refused for account {}{}: {}", action, account, planId == null ? "" : " on plan " + planId, code);
        return failure;
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
