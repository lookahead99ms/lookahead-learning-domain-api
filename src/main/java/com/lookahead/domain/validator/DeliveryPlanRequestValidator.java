package com.lookahead.domain.validator;

import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The shape of a delivery plan write: the plan version the page loaded, and the side a conflict keeps. DLV-1016: moved
 * word for word from DeliveryPlanController; every refusal is 422 {@code DELIVERY_ITEM_INVALID}. The ticket itself is
 * checked by {@link DeliveryPlanItemValidator}.
 */
@Component
public class DeliveryPlanRequestValidator {
    public Long expectedVersion(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("expectedVersion")) throw DeliveryPlanItemValidator.invalid("Send the plan version you loaded as expectedVersion.");
        if (!body.path("expectedVersion").isIntegralNumber()) throw DeliveryPlanItemValidator.invalid("expectedVersion must be the plan's version number.");
        return body.path("expectedVersion").asLong();
    }

    /** The body of a conflict resolution: {@code expectedVersion} and {@code keep} only, {@code keep} a string. */
    public String keep(JsonNode body) {
        if (body == null || !Set.of("expectedVersion", "keep").equals(body.propertyNames()) || !body.path("keep").isString())
            throw DeliveryPlanItemValidator.invalid("Choose dev or local.");
        return body.path("keep").asString();
    }
}
