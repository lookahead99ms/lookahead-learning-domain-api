package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.PlatformReviewModels.Fields;
import com.lookahead.domain.model.PlatformReviewModels.StoredAttachment;
import com.lookahead.domain.model.PlatformReviewModels.Upload;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The checks that left PlatformReviewService (DLV-1016); the full flows stay covered in PlatformReviewServiceTest. */
class PlatformReviewInputValidatorTest {
    private static void refused(ThrowingCallable call, int status, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(status);
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).isEqualTo(message);
        });
    }

    @Test void fieldsAreCheckedAndTrimmed() {
        var clean = PlatformReviewInputValidator.fields(new Fields("problem", "Home", " /learn/core-java ", "  A title  ", "What I saw", "high"));
        assertThat(clean).isEqualTo(new Fields("problem", "Home", "/learn/core-java", "A title", "What I saw", "high"));
        refused(() -> PlatformReviewInputValidator.fields(null), 422, "PLATFORM_REVIEW_INVALID", "The item is incomplete.");
        refused(() -> PlatformReviewInputValidator.fields(new Fields("other", "Home", "", "A", "B", "high")), 422, "PLATFORM_REVIEW_INVALID", "Choose a type.");
        refused(() -> PlatformReviewInputValidator.fields(new Fields("problem", "Nowhere", "", "A", "B", "high")), 422, "PLATFORM_REVIEW_INVALID", "Choose a page.");
        refused(() -> PlatformReviewInputValidator.fields(new Fields("problem", "Home", "", "A", "B", "urgent")), 422, "PLATFORM_REVIEW_INVALID",
                "Choose how important this is.");
        refused(() -> PlatformReviewInputValidator.fields(new Fields("problem", "Home", "", "  ", "B", "high")), 422, "PLATFORM_REVIEW_INVALID", "Add a short title.");
        refused(() -> PlatformReviewInputValidator.fields(new Fields("problem", "Home", "//elsewhere.example", "A", "B", "high")), 422, "PLATFORM_REVIEW_INVALID",
                "The page address must start with / and be on this site, for example /learn/core-java.");
    }

    @Test void textKeepsLineBreaksOnlyWhereAllowedAndRefusesLongOrControlText() {
        assertThat(PlatformReviewInputValidator.text("one\ntwo", 10, true, "too long")).isEqualTo("one\ntwo");
        refused(() -> PlatformReviewInputValidator.text("one\ntwo", 10, false, "too long"), 422, "PLATFORM_REVIEW_INVALID", "Remove unsupported characters from the text.");
        refused(() -> PlatformReviewInputValidator.text("x".repeat(11), 10, true, "too long"), 422, "PLATFORM_REVIEW_INVALID", "too long");
        refused(() -> PlatformReviewInputValidator.text(null, 10, true, "too long"), 422, "PLATFORM_REVIEW_INVALID", "The item is incomplete.");
    }

    @Test void fileNamesLoseControlCharactersAndAreCapped() {
        assertThat(PlatformReviewInputValidator.name(" shot\u0000.png ")).isEqualTo("shot.png");
        assertThat(PlatformReviewInputValidator.name(null)).isEqualTo("image");
        assertThat(PlatformReviewInputValidator.name("n".repeat(300))).hasSize(200);
    }

    @Test void imageCountAndEmptyImagesAreRefusedBeforeAnythingIsStored() {
        var seven = Collections.nCopies(7, new Upload("a.png", "image/png", new byte[] {1}));
        refused(() -> PlatformReviewInputValidator.images(seven, List.<StoredAttachment>of()), 422, "PLATFORM_REVIEW_INVALID", "Attach at most 6 images to one item.");
        refused(() -> PlatformReviewInputValidator.images(List.of(new Upload("a.png", "image/png", new byte[0])), List.of()), 422, "PLATFORM_REVIEW_INVALID",
                "An image is empty.");
        refused(() -> PlatformReviewInputValidator.images(List.of(new Upload("a.png", "image/png", new byte[] {1, 2, 3})), List.of()), 422, "PLATFORM_REVIEW_INVALID",
                "Attach PNG, JPEG or WebP images only.");
        assertThat(PlatformReviewInputValidator.images(null, List.of())).isEmpty();
    }

    @Test void pageDatesMustBeIsoDays() {
        assertThat(PlatformReviewInputValidator.day("2026-11-06")).isEqualTo(LocalDate.of(2026, 11, 6));
        refused(() -> PlatformReviewInputValidator.day("yesterday"), 422, "PLATFORM_REVIEW_INVALID", "Invalid date.");
    }
}
