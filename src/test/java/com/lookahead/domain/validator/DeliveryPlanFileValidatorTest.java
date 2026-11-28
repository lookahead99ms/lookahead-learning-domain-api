package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.util.DeliveryPlanRules;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeliveryPlanFileValidatorTest {
  private final DeliveryPlanFileValidator files = new DeliveryPlanFileValidator(new JsonMapper());

  private AccountFailure refused(byte[] bytes) {
    return catchThrowableOfType(AccountFailure.class, () -> files.plan(bytes));
  }

  /**
   * DLV-1016: one copy of the 413; a file over the limit and an edit the store reports as too large
   * answer it.
   */
  @Test
  void aFileOverTheLimitAnswersTheOne413() {
    AccountFailure one = DeliveryPlanFileValidator.tooLarge();
    assertThat(one.status()).isEqualTo(413);
    assertThat(one.code()).isEqualTo("DELIVERY_PLAN_TOO_LARGE");
    assertThat(one.getMessage()).isEqualTo("The delivery plan exceeds 16 MB.");
    AccountFailure file = refused(new byte[DeliveryPlanRules.MAX_BYTES + 1]);
    assertThat(file.status()).isEqualTo(one.status());
    assertThat(file.code()).isEqualTo(one.code());
    assertThat(file.getMessage()).isEqualTo(one.getMessage());
  }

  @Test
  void aFileThatIsNoPlanIsRefusedInTheUploadersWordsAndAPlanIsRead() {
    assertThat(refused(null).getMessage()).isEqualTo("The uploaded file is empty.");
    assertThat(refused(new byte[0]).code()).isEqualTo("DELIVERY_INBOX_INVALID");
    assertThat(refused("{".getBytes(StandardCharsets.UTF_8)).getMessage())
        .isEqualTo("The uploaded file is not valid JSON.");
    for (String text :
        new String[] {
          "[]",
          "{\"version\":1}",
          "{\"version\":\"1\",\"workItems\":[]}",
          "{\"version\":1,\"workItems\":{}}"
        }) {
      AccountFailure failure = refused(text.getBytes(StandardCharsets.UTF_8));
      assertThat(failure.status()).as(text).isEqualTo(422);
      assertThat(failure.getMessage())
          .as(text)
          .isEqualTo(
              "The uploaded file is not a delivery plan: it needs a numeric version and a workItems"
                  + " array.");
    }
    assertThat(
            files
                .plan("{\"version\":3,\"workItems\":[]}".getBytes(StandardCharsets.UTF_8))
                .path("version")
                .asInt())
        .isEqualTo(3);
  }
}
