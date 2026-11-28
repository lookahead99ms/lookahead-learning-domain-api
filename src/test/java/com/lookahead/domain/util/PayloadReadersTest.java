package com.lookahead.domain.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lookahead.domain.exception.AccountFailure;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayloadReadersTest {
  private static final String ID = "11111111-2222-3333-4444-55555555abcd";

  @Test
  void aUuidWrittenInFullIsReadInLowerUpperOrMixedCase() {
    for (String text :
        new String[] {ID, ID.toUpperCase(Locale.ROOT), "11111111-2222-3333-4444-55555555AbCd"})
      assertThat(PayloadReaders.uuid(text)).as(text).isEqualTo(UUID.fromString(ID));
  }

  @Test
  void anythingElseIsAnInvalidId() {
    for (String text :
        Arrays.asList(
            null,
            "",
            "nope",
            "1-1-1-1-1",
            " " + ID,
            ID + " ",
            "11111111222233334444555555555555",
            "1111111\uFF11-2222-3333-4444-55555555abcd",
            "1111111\u0130-2222-3333-4444-55555555abcd"))
      assertThatThrownBy(() -> PayloadReaders.uuid(text))
          .as(String.valueOf(text))
          .isInstanceOfSatisfying(
              AccountFailure.class,
              failure -> {
                assertThat(failure.status()).isEqualTo(400);
                assertThat(failure.code()).isEqualTo("INVALID_ID");
                assertThat(failure.getMessage()).isEqualTo("A canonical UUID is required");
              });
  }
}
