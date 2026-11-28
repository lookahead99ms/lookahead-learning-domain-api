package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.exception.AccountFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-1016: the ticket checks of the delivery plan, moved here from DeliveryPlanService; one
 * refusal per check, in their order.
 */
class DeliveryPlanItemValidatorTest {
  final ObjectMapper mapper = new ObjectMapper();
  final DeliveryPlanItemValidator items = new DeliveryPlanItemValidator();
  final JsonNode plan =
      mapper.readTree(
          "{\"workflow\":[{\"id\":\"backlog\"},{\"id\":\"done\"}],"
              + "\"priorities\":[{\"id\":\"P1\"},{\"id\":\"P2\"}],"
              + "\"stages\":[{\"id\":\"stage-1\"}]}");

  private ObjectNode item(String changes) {
    ObjectNode item =
        (ObjectNode)
            mapper.readTree(
                "{\"id\":\"DLV-7\",\"title\":\"A"
                    + " ticket\",\"summary\":\"s\",\"type\":\"task\","
                    + "\"statusId\":\"backlog\",\"priorityId\":\"P1\","
                    + "\"stageId\":\"stage-1\"}");
    item.setAll((ObjectNode) mapper.readTree(changes));
    return item;
  }

  private void refused(ObjectNode item, JsonNode onPlan, String message) {
    assertThatThrownBy(() -> items.validate(item, onPlan))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(422);
              assertThat(failure.code()).isEqualTo("DELIVERY_ITEM_INVALID");
              assertThat(failure).hasMessage(message);
            });
  }

  @Test
  void aCompleteTicketPassesWithOrWithoutItsOptionalFields() {
    assertThatCode(() -> items.validate(item("{}"), plan)).doesNotThrowAnyException();
    var bare = item("{}");
    bare.remove("id");
    bare.remove("summary");
    bare.remove("stageId");
    assertThatCode(() -> items.validate(bare, plan)).doesNotThrowAnyException();
    assertThatCode(() -> items.validate(item("{\"stageId\":null}"), plan))
        .doesNotThrowAnyException();
    // A plan without priorities asks for none.
    var noPriority = item("{}");
    noPriority.remove("priorityId");
    assertThatCode(() ->
            items.validate(
                noPriority,
                mapper.readTree(
                    "{\"workflow\":[{\"id\":\"backlog\"}],\"stages\":[{\"id\":\"stage-1\"}]}")))
        .doesNotThrowAnyException();
  }

  @Test
  void eachFieldHasItsOwnRefusal() {
    refused(item("{\"id\":\"-bad\"}"), plan, "The ticket id is not valid.");
    refused(item("{\"id\":7}"), plan, "The ticket id is not valid.");
    refused(item("{\"title\":\" \"}"), plan, "Give the ticket a title of at most 200 characters.");
    refused(
        item("{\"title\":\"" + "t".repeat(201) + "\"}"),
        plan,
        "Give the ticket a title of at most 200 characters.");
    refused(item("{\"title\":7}"), plan, "Give the ticket a title of at most 200 characters.");
    refused(
        item("{\"summary\":\"" + "s".repeat(5001) + "\"}"),
        plan,
        "Keep the summary within 5000 characters.");
    refused(item("{\"summary\":7}"), plan, "Keep the summary within 5000 characters.");
    refused(item("{\"type\":\"Task\"}"), plan, "Choose a ticket type.");
    refused(item("{\"type\":null}"), plan, "Choose a ticket type.");
    refused(item("{\"statusId\":\"unknown\"}"), plan, "Choose a status from the workflow.");
    refused(item("{\"statusId\":7}"), plan, "Choose a status from the workflow.");
    refused(item("{\"priorityId\":\"P9\"}"), plan, "Choose a priority from the plan.");
    refused(item("{\"stageId\":\"stage-9\"}"), plan, "Choose a stage from the plan.");
  }

  @Test
  void aTicketWrongInSeveralWaysIsRefusedForTheFirstCheckInTheFixedOrder() {
    refused(
        item("{\"id\":\"-bad\",\"title\":\"\",\"type\":\"X\",\"statusId\":\"unknown\"}"),
        plan,
        "The ticket id is not valid.");
    refused(
        item("{\"title\":\"\",\"summary\":7,\"type\":\"X\"}"),
        plan,
        "Give the ticket a title of at most 200 characters.");
    refused(
        item("{\"summary\":7,\"type\":\"X\",\"statusId\":\"unknown\"}"),
        plan,
        "Keep the summary within 5000 characters.");
    refused(
        item("{\"type\":\"X\",\"statusId\":\"unknown\",\"priorityId\":\"P9\"}"),
        plan,
        "Choose a ticket type.");
    refused(
        item("{\"statusId\":\"unknown\",\"priorityId\":\"P9\",\"stageId\":\"stage-9\"}"),
        plan,
        "Choose a status from the workflow.");
    refused(
        item("{\"priorityId\":\"P9\",\"stageId\":\"stage-9\"}"),
        plan,
        "Choose a priority from the plan.");
  }
}
