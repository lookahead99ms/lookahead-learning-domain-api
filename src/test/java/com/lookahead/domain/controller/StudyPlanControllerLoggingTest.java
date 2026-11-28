package com.lookahead.domain.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.plan.StudyPlanService;
import com.lookahead.domain.validator.StudyPlanRequestValidator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-969: study plan saves, activations and deletions are one line each once the service returns;
 * refusals name their code.
 */
class StudyPlanControllerLoggingTest {
  final StudyPlanService plans =
      mock(StudyPlanService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
  final StudyPlanController controller =
      new StudyPlanController(plans, new StudyPlanRequestValidator());
  final AccountPrincipal user =
      new AccountPrincipal(
          UUID.randomUUID(), "learner@example.invalid", "Learner Name", true, false);
  final JsonMapper json = JsonMapper.builder().build();
  final UUID plan = UUID.randomUUID();

  @Test
  void planChangesAndRefusalsAreLoggedWithoutThePlanContent() {
    var created =
        json.createObjectNode().put("planId", plan.toString()).put("goal", "Private goal");
    when(plans.create(eq(user.accountId()), any(), any(), eq(false)))
        .thenReturn(new StudyPlanService.Mutation(StudyPlanService.Outcome.CREATED, created));
    when(plans.lifecycle(eq(user.accountId()), eq(plan), any(), any()))
        .thenReturn(new StudyPlanService.Mutation(StudyPlanService.Outcome.CHANGED, created));
    when(plans.activity(eq(user.accountId()), eq(plan), any(), any()))
        .thenReturn(new StudyPlanService.Mutation(StudyPlanService.Outcome.CHANGED, created));
    when(plans.delete(eq(user.accountId()), eq(plan), any(), eq(3L)))
        .thenReturn(
            new StudyPlanService.Mutation(StudyPlanService.Outcome.DELETED, json.nullNode()));
    when(plans.saveVersion(eq(user.accountId()), eq(plan), any(), any()))
        .thenThrow(new IllegalArgumentException("Invalid version reason"));
    String key = UUID.randomUUID().toString();
    try (var log = LogCapture.start()) {
      assertThat(
              controller
                  .create(user, key, json.createObjectNode().put("goal", "Private goal"))
                  .getStatusCode()
                  .value())
          .isEqualTo(201);
      controller.lifecycle(
          user, plan.toString(), key, json.createObjectNode().put("state", "active"));
      controller.lifecycle(
          user, plan.toString(), key, json.createObjectNode().put("state", "archived"));
      controller.activity(user, plan.toString(), key, json.createObjectNode());
      assertThat(
              controller
                  .delete(user, plan.toString(), key, "\"revision-3\"")
                  .getStatusCode()
                  .value())
          .isEqualTo(204);
      assertThatThrownBy(() -> controller.delete(user, plan.toString(), key, null))
          .isInstanceOf(AccountFailure.class);
      assertThatThrownBy(
              () -> controller.versions(user, plan.toString(), key, json.createObjectNode()))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(log.messages())
          .containsExactly(
              "Study plan " + plan + " saved by account " + user.accountId(),
              "Study plan " + plan + " activated by account " + user.accountId(),
              "Study plan " + plan + " archived by account " + user.accountId(),
              "Study plan " + plan + " deleted by account " + user.accountId(),
              "Study plan change plan.delete refused for account "
                  + user.accountId()
                  + " on plan "
                  + plan
                  + ": REVISION_REQUIRED",
              "Study plan change plan.version refused for account "
                  + user.accountId()
                  + " on plan "
                  + plan
                  + ": INVALID_ACCOUNT_PAYLOAD");
      assertThat(log.lines("plan.activate"))
          .singleElement()
          .satisfies(line ->
              assertThat(LogCapture.keys(line))
                  .containsEntry("target.id", plan.toString())
                  .containsEntry("account.id", user.accountId())
                  .containsEntry("event.outcome", "success"));
      assertThat(log.lines("plan.version"))
          .singleElement()
          .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
      log.containsNone(
          "Private goal", "learner@example.invalid", "Learner Name", "Invalid version reason");
    }
  }

  /**
   * DLV-1016: the service answers a typed outcome and this controller chooses the HTTP status.
   * Every route keeps the status, headers, body and {@code http.status} log key it had when the
   * service named the number (201 for a new plan or version, 200 for a change in place, 204 for a
   * delete).
   */
  @Test
  void everyRouteAnswersTheStatusOfItsOutcome() {
    var data = json.createObjectNode().put("planId", plan.toString());
    var created = new StudyPlanService.Mutation(StudyPlanService.Outcome.CREATED, data);
    var changed = new StudyPlanService.Mutation(StudyPlanService.Outcome.CHANGED, data);
    when(plans.create(eq(user.accountId()), any(), any(), anyBoolean())).thenReturn(created);
    when(plans.saveVersion(eq(user.accountId()), eq(plan), any(), any())).thenReturn(created);
    when(plans.rename(eq(user.accountId()), eq(plan), any(), any())).thenReturn(changed);
    when(plans.activity(eq(user.accountId()), eq(plan), any(), any())).thenReturn(changed);
    when(plans.lifecycle(eq(user.accountId()), eq(plan), any(), any())).thenReturn(changed);
    when(plans.delete(eq(user.accountId()), eq(plan), any(), eq(3L)))
        .thenReturn(
            new StudyPlanService.Mutation(StudyPlanService.Outcome.DELETED, json.nullNode()));
    String key = UUID.randomUUID().toString(), id = plan.toString();
    var body = json.createObjectNode();
    try (var log = LogCapture.start()) {
      var answers = new LinkedHashMap<String, ResponseEntity<?>>();
      answers.put("plan.create", controller.create(user, key, body));
      answers.put("plan.import", controller.imports(user, key, body));
      answers.put("plan.version", controller.versions(user, id, key, body));
      answers.put("plan.rename", controller.rename(user, id, key, body));
      answers.put("plan.activity", controller.activity(user, id, key, body));
      answers.put(
          "plan.complete",
          controller.lifecycle(user, id, key, json.createObjectNode().put("state", "completed")));
      answers.put("plan.delete", controller.delete(user, id, key, "\"revision-3\""));
      var expected =
          Map.of(
              "plan.create",
              201,
              "plan.import",
              201,
              "plan.version",
              201,
              "plan.rename",
              200,
              "plan.activity",
              200,
              "plan.complete",
              200,
              "plan.delete",
              204);
      assertThat(answers.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
      answers.forEach((action, answer) -> {
        int status = expected.get(action);
        assertThat(answer.getStatusCode().value()).as(action).isEqualTo(status);
        assertThat(answer.getHeaders().getFirst("Cache-Control")).as(action).isEqualTo("no-store");
        // Only a 201 names the new plan's address; a 204 has no body; every other answer wraps the
        // service's data.
        assertThat(answer.getHeaders().getLocation())
            .as(action)
            .isEqualTo(status == 201 ? URI.create("/api/v1/plans/" + plan) : null);
        if (status == 204) assertThat(answer.getBody()).as(action).isNull();
        else
          assertThat(answer.getBody())
              .as(action)
              .isInstanceOfSatisfying(
                  ApiResponse.class, wrapped -> assertThat(wrapped.data()).isSameAs(data));
        // Progress saves write no success line (only their refusals are logged); every other change
        // writes one with the same number.
        if (action.equals("plan.activity")) assertThat(log.lines(action)).isEmpty();
        else
          assertThat(log.lines(action))
              .as(action)
              .singleElement()
              .satisfies(line ->
                  assertThat(LogCapture.keys(line))
                      .containsEntry("http.status", status)
                      .containsEntry("target.id", id)
                      .containsEntry("event.outcome", "success"));
      });
    }
  }
}
