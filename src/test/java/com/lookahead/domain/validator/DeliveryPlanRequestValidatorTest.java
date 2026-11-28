package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.exception.AccountFailure;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-1016: the request-shape checks of a delivery plan write, moved here from
 * DeliveryPlanController.
 */
class DeliveryPlanRequestValidatorTest {
  final ObjectMapper mapper = new ObjectMapper();
  final DeliveryPlanRequestValidator shapes = new DeliveryPlanRequestValidator();

  private JsonNode json(String text) {
    return mapper.readTree(text);
  }

  @Test
  void theExpectedVersionIsAWholeNumberInAnObject() {
    assertThat(shapes.expectedVersion(json("{\"expectedVersion\":3,\"item\":{}}"))).isEqualTo(3L);
    for (String body : new String[] {"[]", "\"bad\"", "{}", "{\"item\":{}}"})
      refused(
          () -> shapes.expectedVersion(json(body)),
          "Send the plan version you loaded as expectedVersion.");
    refused(
        () -> shapes.expectedVersion(null), "Send the plan version you loaded as expectedVersion.");
    for (String body :
        new String[] {
          "{\"expectedVersion\":\"3\"}", "{\"expectedVersion\":3.5}", "{\"expectedVersion\":null}"
        })
      refused(
          () -> shapes.expectedVersion(json(body)),
          "expectedVersion must be the plan's version number.");
  }

  @Test
  void aConflictChoiceIsTheVersionAndAKeepStringOnly() {
    assertThat(shapes.keep(json("{\"expectedVersion\":3,\"keep\":\"dev\"}"))).isEqualTo("dev");
    // Which words are a choice is the service's check; any string passes here, even with a version
    // of the wrong type.
    assertThat(shapes.keep(json("{\"expectedVersion\":\"3\",\"keep\":\"other\"}")))
        .isEqualTo("other");
    refused(() -> shapes.keep(null), "Choose dev or local.");
    for (String body :
        new String[] {
          "{}",
          "{\"keep\":\"dev\"}",
          "{\"expectedVersion\":3}",
          "{\"expectedVersion\":3,\"keep\":7}",
          "{\"expectedVersion\":3,\"keep\":\"dev\",\"x\":1}",
          "[]"
        }) refused(() -> shapes.keep(json(body)), "Choose dev or local.");
  }

  private static void refused(ThrowingCallable call, String message) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            AccountFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(422);
              assertThat(failure.code()).isEqualTo("DELIVERY_ITEM_INVALID");
              assertThat(failure).hasMessage(message);
            });
  }
}
