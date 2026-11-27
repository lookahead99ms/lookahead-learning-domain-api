package com.lookahead.domain.validator;

import com.lookahead.domain.model.StudyPlanVersion;
import com.lookahead.domain.model.AccountCatalog;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** DLV-1016: the recovery checks of a new plan version, moved here from StudyPlanService.validateRecovery. */
class StudyPlanRecoveryValidatorTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AccountCatalog catalog = mock(AccountCatalog.class);
    final StudyPlanRecoveryValidator recoveries = new StudyPlanRecoveryValidator(catalog);
    final JsonNode pins = mapper.readTree("{\"algorithmVersion\":\"a1\",\"catalogVersion\":\"c1\",\"rankingVersion\":\"r1\",\"origin\":\"generated\"}");

    StudyPlanRecoveryValidatorTest() { when(catalog.canonicalContentId(anyString())).thenAnswer(call -> call.getArgument(0)); }

    private ObjectNode snapshot() {
        return (ObjectNode) mapper.readTree("{\"config\":{\"days\":3,\"dailyHours\":2,\"goalType\":\"interview\"},\"days\":["
                + "{\"day\":1,\"assignments\":[{\"id\":\"lesson-1\",\"kind\":\"new\"}]},"
                + "{\"day\":2,\"assignments\":[{\"id\":\"lesson-2\",\"kind\":\"new\"}]},"
                + "{\"day\":3,\"assignments\":[{\"id\":\"lesson-1:review\",\"kind\":\"review\",\"sourceContentId\":\"lesson-1\",\"requiredSessionId\":\"lesson-1\",\"reviewDueDay\":3}]}],"
                + "\"futureReviews\":[]}");
    }
    private StudyPlanVersion prior(String recovery) { return new StudyPlanVersion(UUID.randomUUID(), snapshot(), pins, mapper.readTree(recovery), mapper.createObjectNode()); }
    private JsonNode recovery(String strategy, int elapsed, int deadline, String deferred) {
        return mapper.readTree("{\"strategy\":\"" + strategy + "\",\"elapsedDays\":" + elapsed + ",\"deadlineDays\":" + deadline + ",\"deferredContentIds\":" + deferred + "}");
    }
    private void refused(StudyPlanVersion prior, JsonNode next, JsonNode provenance, JsonNode recovery, String reason, String message) {
        assertThatThrownBy(() -> recoveries.validate(prior, next, provenance, recovery, reason)).isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test void anUnchangedPlanPassesAsAnUpdateAndAsARecoveryInsideTheWindow() {
        assertThatCode(() -> recoveries.validate(prior("{}"), snapshot(), pins, recovery("none", 0, 3, "[]"), "update-plan")).doesNotThrowAnyException();
        assertThatCode(() -> recoveries.validate(prior("{}"), snapshot(), pins, recovery("fixed-window", 1, 3, "[]"), "recovery")).doesNotThrowAnyException();
    }
    @Test void theRecoveryFieldsAreCheckedFirstInTheirOrder() {
        var prior = prior("{\"elapsedDays\":1}");
        refused(prior, snapshot(), pins, mapper.readTree("{\"strategy\":\"none\",\"surprise\":1}"), "update-plan", "Unknown field: surprise");
        refused(prior, snapshot(), pins, mapper.readTree("{\"strategy\":7}"), "update-plan", "Invalid strategy");
        refused(prior, snapshot(), pins, mapper.readTree("{\"strategy\":\"none\",\"elapsedDays\":181,\"deadlineDays\":3}"), "update-plan", "Invalid elapsedDays");
        refused(prior, snapshot(), pins, mapper.readTree("{\"strategy\":\"none\",\"elapsedDays\":1,\"deadlineDays\":0}"), "update-plan", "Invalid deadlineDays");
        refused(prior, snapshot(), pins, recovery("none", 3, 2, "[]"), "update-plan", "Elapsed days exceed this deadline");
        refused(prior, snapshot(), pins, recovery("none", 1, 4, "[]"), "update-plan", "Snapshot horizon must match deadline");
        refused(prior, snapshot(), pins, recovery("none", 1, 3, "\"lesson-1\""), "update-plan", "Invalid deferred content list");
        refused(prior, snapshot(), pins, recovery("none", 1, 3, "[\"unknown\"]"), "update-plan", "Unknown deferred content");
        refused(prior, snapshot(), pins, recovery("none", 1, 3, "[7]"), "update-plan", "Unknown deferred content");
        refused(prior, snapshot(), pins, recovery("none", 0, 3, "[]"), "update-plan", "Recovery cannot move elapsed time backwards");
    }
    @Test void aDeferredSessionLedgerMustMatchTheOriginalSnapshot() {
        var prior = prior("{}");
        ObjectNode ledger = (ObjectNode) recovery("none", 0, 3, "[]");
        ledger.set("deferredSessions", mapper.readTree("7"));
        refused(prior, snapshot(), pins, ledger, "update-plan", "Invalid deferred session ledger");
        ledger.set("deferredSessions", mapper.readTree("[{\"assignment\":{\"id\":\"lesson-2\",\"kind\":\"new\"},\"originalDay\":2,\"reason\":\"because\"}]"));
        refused(prior, snapshot(), pins, ledger, "update-plan", "Invalid deferral reason");
        ledger.set("deferredSessions", mapper.readTree("[{\"assignment\":{\"id\":\"lesson-2\",\"kind\":\"new\"},\"originalDay\":1,\"reason\":\"daily-budget\"}]"));
        refused(prior, snapshot(), pins, ledger, "update-plan", "Deferred session differs from original snapshot");
        ledger.set("deferredSessions", mapper.readTree("[{\"assignment\":{\"id\":\"lesson-2\",\"kind\":\"new\"},\"originalDay\":null,\"reason\":\"daily-budget\"}]"));
        refused(prior, snapshot(), pins, ledger, "update-plan", "Deferred session differs from original snapshot");
        ledger.set("deferredSessions", mapper.readTree("[{\"assignment\":{\"id\":\"lesson-2\",\"kind\":\"new\"},\"originalDay\":2,\"reason\":\"daily-budget\"}]"));
        assertThatCode(() -> recoveries.validate(prior, snapshot(), pins, ledger, "update-plan")).doesNotThrowAnyException();
    }
    @Test void aPlanUpdateUsesStrategyNoneAndKeepsAnInterviewDeadline() {
        refused(prior("{}"), snapshot(), pins, recovery("fixed-window", 0, 3, "[]"), "update-plan", "A plan update must use strategy none");
        ObjectNode longer = snapshot(); ((ObjectNode) longer.path("config")).put("days", 4);
        refused(prior("{}"), longer, pins, recovery("none", 0, 4, "[]"), "update-plan", "Move an interview deadline only with explicit extension");
    }
    @Test void aRecoveryKeepsTheBudgetThePinsTheOriginAndTheDeadlineAndAnExtensionMovesItLater() {
        var prior = prior("{}");
        ObjectNode moreHours = snapshot(); ((ObjectNode) moreHours.path("config")).put("dailyHours", 3);
        refused(prior, moreHours, pins, recovery("fixed-window", 0, 3, "[]"), "recovery", "Recovery cannot increase the daily budget");
        ObjectNode otherCatalog = (ObjectNode) pins.deepCopy(); otherCatalog.put("catalogVersion", "c2");
        refused(prior, snapshot(), otherCatalog, recovery("fixed-window", 0, 3, "[]"), "recovery", "Recovery must preserve version pins");
        ObjectNode otherOrigin = (ObjectNode) pins.deepCopy(); otherOrigin.put("origin", "import");
        refused(prior, snapshot(), otherOrigin, recovery("fixed-window", 0, 3, "[]"), "recovery", "Recovery must preserve snapshot origin");
        refused(prior, snapshot(), pins, recovery("none", 0, 3, "[]"), "recovery", "Recovery must preserve the interview deadline");
        refused(prior, snapshot(), pins, recovery("explicit-extension", 0, 3, "[]"), "extend-deadline", "Extension must explicitly move the deadline later");
        refused(prior, snapshot(), pins, recovery("fixed-window", 0, 3, "[]"), "extend-deadline", "Extension must explicitly move the deadline later");
    }
    @Test void aRecoveryMayNotAlterSessionsOrDropWorkWithoutDisclosingIt() {
        var prior = prior("{}");
        ObjectNode altered = snapshot(); ((ObjectNode) altered.path("days").get(1).path("assignments").get(0)).put("kind", "review");
        refused(prior, altered, pins, recovery("fixed-window", 0, 3, "[]"), "recovery", "Recovery cannot replace or alter scheduled sessions");
        ObjectNode added = snapshot(); ((ArrayNode) added.path("days").get(1).path("assignments")).addObject().put("id", "lesson-9").put("kind", "new");
        refused(prior, added, pins, recovery("fixed-window", 0, 3, "[]"), "recovery", "Recovery cannot replace or alter scheduled sessions");
        ObjectNode dropped = snapshot(); ((ArrayNode) dropped.path("days").get(1).path("assignments")).removeAll();
        refused(prior, dropped, pins, recovery("fixed-window", 0, 3, "[]"), "recovery", "Omitted work must be disclosed as deferred");
        assertThatCode(() -> recoveries.validate(prior, dropped, pins, recovery("fixed-window", 0, 3, "[\"lesson-2\"]"), "recovery")).doesNotThrowAnyException();
    }
    @Test void onlyTheTimingOfAReviewMayChangeAndItMustFollowItsParent() {
        var prior = prior("{}");
        ObjectNode early = snapshot(); ((ObjectNode) early.path("days").get(2).path("assignments").get(0)).put("reviewDueDay", 2);
        refused(prior, early, pins, recovery("fixed-window", 0, 3, "[]"), "recovery", "Invalid reviewDueDay");
        ObjectNode wrongOrigin = snapshot(); ((ObjectNode) wrongOrigin.path("days").get(2).path("assignments").get(0)).put("reviewFromDay", 2);
        refused(prior, wrongOrigin, pins, recovery("fixed-window", 0, 3, "[]"), "recovery", "Review origin must match its parent day");
        ObjectNode sameOrigin = snapshot(); ((ObjectNode) sameOrigin.path("days").get(2).path("assignments").get(0)).put("reviewFromDay", 1);
        assertThatCode(() -> recoveries.validate(prior, sameOrigin, pins, recovery("fixed-window", 0, 3, "[]"), "recovery")).doesNotThrowAnyException();
    }
}
