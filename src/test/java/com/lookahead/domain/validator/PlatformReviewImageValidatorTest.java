package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.service.review.PlatformReviewTestImages;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PlatformReviewImageValidatorTest {
  byte[] riff(int declared, int chunkSize, int payloadBytes) {
    var bytes = ByteBuffer.allocate(20 + payloadBytes).order(ByteOrder.LITTLE_ENDIAN);
    bytes
        .put(new byte[] {'R', 'I', 'F', 'F'})
        .putInt(declared)
        .put(new byte[] {'W', 'E', 'B', 'P', 'V', 'P', '8', 'L'})
        .putInt(chunkSize);
    return bytes.array();
  }

  @Test
  void rejectsUnsignedChunkOverflowAndMissingPadding() {
    for (int size : new int[] {-1, Integer.MAX_VALUE, Integer.MIN_VALUE, 2}) {
      assertThatThrownBy(() -> PlatformReviewImageValidator.clean("image/webp", riff(12, size, 0)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("damaged");
    }
    assertThatThrownBy(() -> PlatformReviewImageValidator.clean("image/webp", riff(13, 1, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsShortRiffAndPartialChunkHeader() {
    for (int declared : new int[] {0, 3, 5, 11, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
      assertThatThrownBy(
              () -> PlatformReviewImageValidator.clean("image/webp", riff(declared, 0, 0)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void keepsValidPaddedChunkAndStripsMetadata() {
    byte[] valid = riff(14, 1, 2);
    assertThat(PlatformReviewImageValidator.clean("image/webp", valid).bytes()).isEqualTo(valid);
    byte[] cleaned =
        PlatformReviewImageValidator.clean("image/webp", PlatformReviewTestImages.webpWithExif())
            .bytes();
    assertThat(PlatformReviewTestImages.contains(cleaned, "EXIF")).isFalse();
  }
}
