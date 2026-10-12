package com.lookahead.domain.review.platform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlatformReviewImagesTest {
    byte[] riff(int declared, int chunkSize, int payloadBytes) {
        var bytes=ByteBuffer.allocate(20+payloadBytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'R','I','F','F'}).putInt(declared).put(new byte[]{'W','E','B','P','V','P','8','L'}).putInt(chunkSize);
        return bytes.array();
    }
    @Test void rejectsUnsignedChunkOverflowAndMissingPadding() {
        for(int size : new int[]{-1,Integer.MAX_VALUE,Integer.MIN_VALUE,2}) {
            assertThatThrownBy(()->PlatformReviewImages.clean("image/webp",riff(12,size,0)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("damaged");
        }
        assertThatThrownBy(()->PlatformReviewImages.clean("image/webp",riff(13,1,1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsShortRiffAndPartialChunkHeader() {
        for(int declared : new int[]{0,3,5,11,Integer.MAX_VALUE,Integer.MIN_VALUE,-1}) {
            assertThatThrownBy(()->PlatformReviewImages.clean("image/webp",riff(declared,0,0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void keepsValidPaddedChunkAndStripsMetadata() {
        byte[] valid=riff(14,1,2);
        assertThat(PlatformReviewImages.clean("image/webp",valid).bytes()).isEqualTo(valid);
        byte[] cleaned=PlatformReviewImages.clean("image/webp",PlatformReviewTestImages.webpWithExif()).bytes();
        assertThat(PlatformReviewTestImages.contains(cleaned,"EXIF")).isFalse();
    }
}
