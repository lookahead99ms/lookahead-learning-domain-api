package com.lookahead.domain.persistence.dynamodb;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import static org.assertj.core.api.Assertions.*;

class StudyPlanDocumentCodecTest {
    private final StudyPlanDocumentCodec codec = new StudyPlanDocumentCodec();

    @ParameterizedTest @ValueSource(ints = {1, 245759, 245760, 245761, 8388608})
    void roundTripsEveryBoundaryIncludingMaximumIngress(int length) {
        byte[] original = new byte[length];
        new Random(970).nextBytes(original);
        var encoded = codec.encode(original);
        assertThat(encoded.manifest().byteLength()).isEqualTo(length);
        assertThat(encoded.manifest().chunkCount()).isEqualTo((length + 245759) / 245760);
        assertThat(encoded.chunks()).allSatisfy(c -> assertThat(c.asByteArray().length).isBetween(1, 245760));
        assertThat(codec.decode(encoded.manifest(), encoded.chunks())).isEqualTo(original);
        original[0] ^= 1;
        assertThat(codec.decode(encoded.manifest(), encoded.chunks())).isNotEqualTo(original);
        assertThatThrownBy(() -> encoded.chunks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void preservesUtf8CharactersSplitAcrossChunkBoundary() {
        byte[] input = (" ".repeat(245759) + "🙂é日本語").getBytes(StandardCharsets.UTF_8);
        var encoded = codec.encode(input);
        assertThat(codec.decode(encoded.manifest(), encoded.chunks())).isEqualTo(input);
    }

    @Test void rejectsMissingReorderedTruncatedDuplicatedAndCorruptedChunks() {
        byte[] bytes = new byte[2 * 245760 + 7];
        new Random(2).nextBytes(bytes);
        var encoded = codec.encode(bytes);
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded.manifest(), encoded.chunks().subList(0, 2)));
        var swapped = new ArrayList<>(encoded.chunks());
        Collections.swap(swapped, 0, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded.manifest(), swapped));
        var duplicated = new ArrayList<>(encoded.chunks());
        duplicated.set(1, duplicated.getFirst());
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded.manifest(), duplicated));
        var truncated = new ArrayList<>(encoded.chunks());
        truncated.set(0, SdkBytes.fromByteArray(new byte[1]));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded.manifest(), truncated));
        var corrupted = new ArrayList<>(encoded.chunks());
        corrupted.set(2, SdkBytes.fromByteArray(new byte[7]));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded.manifest(), corrupted));
    }

    @Test void rejectsInvalidSizesVersionsAndManifestDigestsBeforeAllocation() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.encode(new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.encode(new byte[8388609]));
        String hash = "a".repeat(64);
        for (int length : new int[]{0, -1, 8388609, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new StudyPlanDocumentCodec.Manifest(1, length, 1, hash));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new StudyPlanDocumentCodec.Manifest(2, 1, 1, hash));
        assertThatIllegalArgumentException().isThrownBy(() -> new StudyPlanDocumentCodec.Manifest(1, 1, 2, hash));
        assertThatIllegalArgumentException().isThrownBy(() -> new StudyPlanDocumentCodec.Manifest(1, 1, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new StudyPlanDocumentCodec.Manifest(1, 1, 1, "ABC"));
        var manifest = new StudyPlanDocumentCodec.Manifest(1, 1, 1, hash);
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(manifest, List.of(SdkBytes.fromByteArray(new byte[]{1}))));
    }
}
