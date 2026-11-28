package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.util.DeliveryPlanRules;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks a delivery plan file before anything is written: the file uploaded from the Delivery Plan
 * page and the file a content release carries. DLV-1016: this check was a method of the delivery
 * plan store.
 */
public final class DeliveryPlanFileValidator {
  private final ObjectMapper mapper;

  public DeliveryPlanFileValidator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * The bytes as a plan, or 413 {@code DELIVERY_PLAN_TOO_LARGE} / 422 {@code
   * DELIVERY_INBOX_INVALID} in the uploader's words: the file must read as a plan object with a
   * numeric version and a workItems array.
   */
  public ObjectNode plan(byte[] bytes) {
    if (bytes == null || bytes.length == 0)
      throw new AccountFailure(422, "DELIVERY_INBOX_INVALID", "The uploaded file is empty.");
    if (bytes.length > DeliveryPlanRules.MAX_BYTES) throw tooLarge();
    JsonNode node;
    try {
      node = mapper.readTree(bytes);
    } catch (JacksonException broken) {
      throw new AccountFailure(
          422, "DELIVERY_INBOX_INVALID", "The uploaded file is not valid JSON.");
    }
    if (!(node instanceof ObjectNode plan)
        || !plan.path("workItems").isArray()
        || !plan.path("version").isIntegralNumber())
      throw new AccountFailure(
          422,
          "DELIVERY_INBOX_INVALID",
          "The uploaded file is not a delivery plan: it needs a numeric version and a workItems"
              + " array.");
    return plan;
  }

  /**
   * The one 413 of a delivery plan over 16 MB: an uploaded or released file ({@link #plan}) and an
   * edited plan that the store would write larger than that ({@code DeliveryPlanService.save},
   * where no file passes this validator).
   */
  public static AccountFailure tooLarge() {
    return new AccountFailure(413, "DELIVERY_PLAN_TOO_LARGE", "The delivery plan exceeds 16 MB.");
  }
}
