package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks a delivery plan ticket against the plan it goes on: its id, title, summary, type, and a status, priority and
 * stage the plan knows. DLV-1016: this is DeliveryPlanService.validate with its helper, moved here word for word and in
 * the same order; every refusal is 422 {@code DELIVERY_ITEM_INVALID}.
 */
@Component
public class DeliveryPlanItemValidator {
    public void validate(ObjectNode item, JsonNode plan) {
        if (item.has("id") && !(item.path("id").isString() && item.path("id").asString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) throw invalid("The ticket id is not valid.");
        if (!item.path("title").isString() || item.path("title").asString().isBlank() || item.path("title").asString().length() > 200) throw invalid("Give the ticket a title of at most 200 characters.");
        if (item.has("summary") && !item.path("summary").isString() || item.path("summary").asString().length() > 5000) throw invalid("Keep the summary within 5000 characters.");
        if (!item.path("type").isString() || !item.path("type").asString().matches("[a-z][a-z-]{0,39}")) throw invalid("Choose a ticket type.");
        if (!item.path("statusId").isString() || !ids(plan, "workflow").contains(item.path("statusId").asString())) throw invalid("Choose a status from the workflow.");
        if (!plan.path("priorities").isEmpty() && !ids(plan, "priorities").contains(item.path("priorityId").asString())) throw invalid("Choose a priority from the plan.");
        if (item.has("stageId") && !item.path("stageId").isNull() && !ids(plan, "stages").contains(item.path("stageId").asString())) throw invalid("Choose a stage from the plan.");
    }

    private static Set<String> ids(JsonNode plan, String field) {
        var ids = new HashSet<String>();
        for (JsonNode entry : plan.path(field)) if (entry.path("id").isString()) ids.add(entry.path("id").asString());
        return ids;
    }

    public static AccountFailure invalid(String message) { return new AccountFailure(422, "DELIVERY_ITEM_INVALID", message); }
}
