package com.lookahead.domain.controller;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.plan.StudyPlanService;
import com.lookahead.domain.util.PayloadReaders;
import com.lookahead.domain.validator.StudyPlanRequestValidator;
import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;
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

@RestController
@Profile("accounts")
@RequestMapping("/api/v1")
public class StudyPlanController {
  private final StudyPlanService planService;
  private final StudyPlanRequestValidator shapes;

  public StudyPlanController(StudyPlanService planService, StudyPlanRequestValidator shapes) {
    this.planService = planService;
    this.shapes = shapes;
  }

  @GetMapping("/account-catalog")
  ResponseEntity<?> catalog() {
    return ok(planService.catalogMetadata());
  }

  @GetMapping("/plans")
  ResponseEntity<?> list(
      @AuthenticationPrincipal AccountPrincipal user,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String after) {
    return ok(planService.list(user.accountId(), limit, after));
  }

  @GetMapping("/plans/{id}")
  ResponseEntity<?> get(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id) {
    return ok(planService.get(user.accountId(), PayloadReaders.uuid(id), null));
  }

  @GetMapping("/plans/{id}/versions/{version}")
  ResponseEntity<?> version(
      @AuthenticationPrincipal AccountPrincipal user,
      @PathVariable String id,
      @PathVariable String version) {
    return ok(
        planService.get(user.accountId(), PayloadReaders.uuid(id), PayloadReaders.uuid(version)));
  }

  @PostMapping("/plans")
  ResponseEntity<?> create(
      @AuthenticationPrincipal AccountPrincipal user,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    return logged(
        "plan.create",
        user,
        null,
        () -> planService.create(user.accountId(), key(key), body, false),
        "Study plan {} saved by account {}");
  }

  @PostMapping("/plans/imports")
  ResponseEntity<?> imports(
      @AuthenticationPrincipal AccountPrincipal user,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    return logged(
        "plan.import",
        user,
        null,
        () -> planService.create(user.accountId(), key(key), body, true),
        "Study plan {} imported by account {}");
  }

  @PostMapping("/plans/{id}/name")
  ResponseEntity<?> rename(
      @AuthenticationPrincipal AccountPrincipal user,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    return logged(
        "plan.rename",
        user,
        id,
        () -> planService.rename(user.accountId(), PayloadReaders.uuid(id), key(key), body),
        "Study plan {} renamed by account {}");
  }

  @PostMapping("/plans/{id}/activity")
  ResponseEntity<?> activity(
      @AuthenticationPrincipal AccountPrincipal user,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    // Progress is saved many times a session: only its refusals are logged.
    return logged(
        "plan.activity",
        user,
        id,
        () -> planService.activity(user.accountId(), PayloadReaders.uuid(id), key(key), body),
        null);
  }

  @PostMapping("/plans/{id}/lifecycle")
  ResponseEntity<?> lifecycle(
      @AuthenticationPrincipal AccountPrincipal user,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    String state = shapes.lifecycleState(body);
    return logged(
        lifecycleAction(state),
        user,
        id,
        () -> planService.lifecycle(user.accountId(), PayloadReaders.uuid(id), key(key), body),
        "Study plan {} " + lifecycleDone(state) + " by account {}");
  }

  @PostMapping("/plans/{id}/versions")
  ResponseEntity<?> versions(
      @AuthenticationPrincipal AccountPrincipal user,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    return logged(
        "plan.version",
        user,
        id,
        () -> planService.saveVersion(user.accountId(), PayloadReaders.uuid(id), key(key), body),
        "Study plan {} saved as a new version by account {}");
  }

  @DeleteMapping("/plans/{id}")
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AccountPrincipal user,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestHeader(value = "If-Match", required = false) String expected) {
    return logged(
        "plan.delete",
        user,
        id,
        () -> {
          long revision = shapes.revision(expected);
          return planService.delete(user.accountId(), PayloadReaders.uuid(id), key(key), revision);
        },
        "Study plan {} deleted by account {}");
  }

  /**
   * DLV-969: one line per plan change once it has committed (the service's transaction ends before
   * this returns), or one WARN line with the refusal code. Never the plan's goal, name or content.
   * {@code success} null: refusals only.
   */
  // Exception, decided 2026-11-25 (DLV-1016): the controller writes the log line of each plan
  // change, not the service.
  // The same wrapper logs the refusals that happen here before the service is called (a path id
  // that is not a UUID,
  // a bad Idempotency-Key, a missing or malformed If-Match) under the same action name; the service
  // never sees
  // those, so moving the lines into it would lose cases or split one line shape over two classes.
  private ResponseEntity<?> logged(
      String action,
      AccountPrincipal user,
      String pathId,
      Supplier<StudyPlanService.Mutation> call,
      String success) {
    UUID account = user == null ? null : user.accountId();
    String planId = pathId != null && pathId.matches("[0-9a-f-]{36}") ? pathId : null;
    StudyPlanService.Mutation result;
    try {
      result = call.get();
    } catch (AccountFailure refused) {
      throw refusal(action, account, planId, refused.code(), refused);
    } catch (IllegalArgumentException invalid) {
      throw refusal(action, account, planId, "INVALID_ACCOUNT_PAYLOAD", invalid);
    }
    if (success != null) {
      String id =
          planId != null
              ? planId
              : result.data() == null ? null : result.data().path("planId").asText(null);
      EventLog.success(EventLog.EVENT, action)
          .addKeyValue("account.id", account)
          .addKeyValue("target.id", id)
          .addKeyValue("http.status", status(result.outcome()))
          .log(success, id, account);
    }
    return mutation(result);
  }

  private static RuntimeException refusal(
      String action, UUID account, String planId, String code, RuntimeException failure) {
    EventLog.refused(EventLog.EVENT, action, code)
        .addKeyValue("account.id", account)
        .addKeyValue("target.id", planId)
        .log(
            "Study plan change {} refused for account {}{}: {}",
            action,
            account,
            planId == null ? "" : " on plan " + planId,
            code);
    return failure;
  }

  // Part of the log wrapper above (same exception): the action name and the word of a lifecycle
  // change's log line.
  private static String lifecycleAction(String state) {
    return switch (state) {
      case "active" -> "plan.activate";
      case "archived" -> "plan.archive";
      case "completed" -> "plan.complete";
      default -> "plan.lifecycle";
    };
  }

  private static String lifecycleDone(String state) {
    return switch (state) {
      case "active" -> "activated";
      case "archived", "completed" -> state;
      default -> "changed";
    };
  }

  private UUID key(String value) {
    return shapes.idempotencyKey(value);
  }

  private ResponseEntity<?> ok(JsonNode data) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(data));
  }

  /**
   * The HTTP status of a plan change: 201 for a new plan or version, 200 for a change in place, 204
   * for a delete (DLV-1016: the service named it).
   */
  private static int status(StudyPlanService.Outcome outcome) {
    return switch (outcome) {
      case CREATED -> 201;
      case CHANGED -> 200;
      case DELETED -> 204;
    };
  }

  private ResponseEntity<?> mutation(StudyPlanService.Mutation result) {
    if (result.outcome() == StudyPlanService.Outcome.DELETED) {
      return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
    var response =
        ResponseEntity.status(status(result.outcome())).header("Cache-Control", "no-store");
    if (result.outcome() == StudyPlanService.Outcome.CREATED) {
      response.location(URI.create("/api/v1/plans/" + result.data().path("planId").asText()));
    }
    return response.body(ApiResponse.success(result.data()));
  }
}
