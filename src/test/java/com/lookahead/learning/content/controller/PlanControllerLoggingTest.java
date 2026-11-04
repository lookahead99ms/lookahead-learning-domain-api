package com.lookahead.learning.content.controller;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.LogCapture;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.PlanService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969: study plan saves, activations and deletions are one line each once the service returns; refusals name their code. */
class PlanControllerLoggingTest {
    final PlanService plans = mock(PlanService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final PlanController controller = new PlanController(plans);
    final AccountPrincipal user = new AccountPrincipal(UUID.randomUUID(), "learner@example.invalid", "Learner Name", true, false);
    final JsonMapper json = JsonMapper.builder().build();
    final UUID plan = UUID.randomUUID();

    @Test void planChangesAndRefusalsAreLoggedWithoutThePlanContent() {
        var created = json.createObjectNode().put("planId", plan.toString()).put("goal", "Private goal");
        when(plans.create(eq(user.accountId()), any(), any(), eq(false))).thenReturn(new PlanService.Mutation(201, created));
        when(plans.lifecycle(eq(user.accountId()), eq(plan), any(), any())).thenReturn(new PlanService.Mutation(200, created));
        when(plans.activity(eq(user.accountId()), eq(plan), any(), any())).thenReturn(new PlanService.Mutation(200, created));
        when(plans.delete(eq(user.accountId()), eq(plan), any(), eq(3L))).thenReturn(new PlanService.Mutation(204, json.nullNode()));
        when(plans.saveVersion(eq(user.accountId()), eq(plan), any(), any())).thenThrow(new IllegalArgumentException("Invalid version reason"));
        String key = UUID.randomUUID().toString();
        try (var log = LogCapture.start()) {
            assertThat(controller.create(user, key, json.createObjectNode().put("goal", "Private goal")).getStatusCode().value()).isEqualTo(201);
            controller.lifecycle(user, plan.toString(), key, json.createObjectNode().put("state", "active"));
            controller.lifecycle(user, plan.toString(), key, json.createObjectNode().put("state", "archived"));
            controller.activity(user, plan.toString(), key, json.createObjectNode());
            assertThat(controller.delete(user, plan.toString(), key, "\"revision-3\"").getStatusCode().value()).isEqualTo(204);
            assertThatThrownBy(() -> controller.delete(user, plan.toString(), key, null)).isInstanceOf(AccountFailure.class);
            assertThatThrownBy(() -> controller.versions(user, plan.toString(), key, json.createObjectNode())).isInstanceOf(IllegalArgumentException.class);

            assertThat(log.messages()).containsExactly(
                    "Study plan " + plan + " saved by account " + user.accountId(),
                    "Study plan " + plan + " activated by account " + user.accountId(),
                    "Study plan " + plan + " archived by account " + user.accountId(),
                    "Study plan " + plan + " deleted by account " + user.accountId(),
                    "Study plan change plan.delete refused for account " + user.accountId() + " on plan " + plan + ": REVISION_REQUIRED",
                    "Study plan change plan.version refused for account " + user.accountId() + " on plan " + plan + ": INVALID_ACCOUNT_PAYLOAD");
            assertThat(log.lines("plan.activate")).singleElement().satisfies(line -> assertThat(LogCapture.keys(line))
                    .containsEntry("target.id", plan.toString()).containsEntry("account.id", user.accountId()).containsEntry("event.outcome", "success"));
            assertThat(log.lines("plan.version")).singleElement().satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
            log.containsNone("Private goal", "learner@example.invalid", "Learner Name", "Invalid version reason");
        }
    }
}
