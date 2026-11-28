package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.dto.SupportFeedback;
import com.lookahead.domain.dto.SupportFeedbackAttachment;
import com.lookahead.domain.exception.AccountFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * DLV-1016: the checks of a support request, moved here from the first lines of
 * SupportFeedbackService.submit.
 */
class SupportFeedbackValidatorTest {
  static final String KEY = "request-key-0001";
  final SupportFeedbackValidator checks = new SupportFeedbackValidator();

  private static SupportFeedback feedback(
      String type,
      String subject,
      String message,
      String replyTo,
      List<SupportFeedbackAttachment> images) {
    return new SupportFeedback(type, subject, message, replyTo, images);
  }

  private static SupportFeedback valid(List<SupportFeedbackAttachment> images) {
    return feedback("problem", "Subject", "Message", "learner@example.invalid", images);
  }

  private static byte[] picture(String format) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), format, out);
    return out.toByteArray();
  }

  private static SupportFeedbackAttachment attachment(byte[] bytes) {
    return new SupportFeedbackAttachment("a", Base64.getEncoder().encodeToString(bytes));
  }

  @Test
  void validFieldsAnswerTheAttachmentsToCheck() {
    assertThat(checks.fields(KEY, valid(null))).isEmpty();
    var images =
        List.of(
            new SupportFeedbackAttachment("a", "x"),
            new SupportFeedbackAttachment("b", "y"),
            new SupportFeedbackAttachment("c", "z"));
    assertThat(checks.fields(KEY, valid(images))).isSameAs(images);
  }

  @Test
  void eachFieldHasItsOwnRefusalInTheFixedOrder() {
    for (String key : Arrays.asList(null, "short", "has space in the key", "k".repeat(81)))
      refused(() -> checks.fields(key, valid(null)), "A valid request key is required");
    refused(() -> checks.fields("short", null), "A valid request key is required");
    refused(() -> checks.fields(KEY, null), "Choose a feedback type");
    refused(() -> checks.fields(KEY, feedback(null, "", "", "", null)), "Choose a feedback type");
    refused(
        () -> checks.fields(KEY, feedback("praise", "", "", "", null)), "Choose a feedback type");
    refused(
        () -> checks.fields(KEY, feedback("feedback", " ", "Message", "bad", null)),
        "Message fields exceed their limits");
    refused(
        () -> checks.fields(KEY, feedback("feedback", "s".repeat(161), "Message", "bad", null)),
        "Message fields exceed their limits");
    refused(
        () -> checks.fields(KEY, feedback("question", "Subject", null, "bad", null)),
        "Message fields exceed their limits");
    refused(
        () -> checks.fields(KEY, feedback("question", "Subject", "m".repeat(5001), "bad", null)),
        "Message fields exceed their limits");
    refused(
        () ->
            checks.fields(
                KEY,
                feedback("question", "Two\nlines", "Message", "learner@example.invalid", null)),
        "Check the subject and reply email");
    refused(
        () ->
            checks.fields(
                KEY,
                feedback(
                    "question", "Carriage\rreturn", "Message", "learner@example.invalid", null)),
        "Check the subject and reply email");
    refused(
        () ->
            checks.fields(KEY, feedback("question", "Subject", "Message", "not an address", null)),
        "Check the subject and reply email");
    refused(
        () -> checks.fields(KEY, feedback("question", "Subject", "Message", null, null)),
        "Check the subject and reply email");
    var four = Collections.nCopies(4, new SupportFeedbackAttachment("a", "x"));
    refused(() -> checks.fields(KEY, valid(four)), "Attach at most three PNG or JPEG images");
    refused(
        () -> checks.fields(KEY, feedback("question", "Subject", "Message", "bad", four)),
        "Check the subject and reply email");
  }

  @Test
  void aPngOrJpegIsWrittenAgainAsPngAndItsDecodedBytesAreKeptForTheFingerprint() throws Exception {
    byte[] png = picture("png"), jpeg = picture("jpg");
    var checked = checks.images(List.of(attachment(png), attachment(jpeg)));
    assertThat(checked.images())
        .extracting(image -> image.name())
        .containsExactly("screenshot-1.png", "screenshot-2.png");
    assertThat(checked.decoded()).containsExactly(png, jpeg);
    for (var image : checked.images()) {
      var read = ImageIO.read(new ByteArrayInputStream(image.bytes()));
      assertThat(read.getWidth()).isEqualTo(2);
      assertThat(read.getHeight()).isEqualTo(3);
      assertThat(Arrays.copyOf(image.bytes(), 4)).containsExactly(0x89, 'P', 'N', 'G');
    }
    assertThat(checks.images(List.of()).images()).isEmpty();
  }

  @Test
  void aWrongImageHasItsOwnRefusal() throws Exception {
    refused(
        () -> checks.images(Arrays.asList((SupportFeedbackAttachment) null)),
        "Each image must be at most 2 MB");
    refused(
        () -> checks.images(List.of(new SupportFeedbackAttachment("a", null))),
        "Each image must be at most 2 MB");
    refused(
        () -> checks.images(List.of(new SupportFeedbackAttachment("a", "A".repeat(2_800_001)))),
        "Each image must be at most 2 MB");
    refused(
        () -> checks.images(List.of(attachment(new byte[2 * 1024 * 1024 + 1]))),
        "Images exceed the 5 MB total limit");
    byte[] png = picture("png");
    byte[] large = Arrays.copyOf(png, 2 * 1024 * 1024);
    // Three images of 2 MB: the third takes the total past 5 MB.
    refused(
        () -> checks.images(List.of(attachment(large), attachment(large), attachment(large))),
        "Images exceed the 5 MB total limit");
    refused(
        () -> checks.images(List.of(attachment("not an image".getBytes()))),
        "Only PNG and JPEG images are supported");
    refused(
        () -> checks.images(List.of(attachment(picture("gif")))),
        "Unsupported image format or dimensions");
    refused(
        () -> checks.images(List.of(attachment(png), attachment("not an image".getBytes()))),
        "Only PNG and JPEG images are supported");
  }

  /**
   * Not a refusal of this class: the service answers these as 422 with its own log line, as it did
   * before the move.
   */
  @Test
  void bytesThatCannotBeDecodedFailWithTheDecodersOwnException() {
    assertThatThrownBy(
            () -> checks.images(List.of(new SupportFeedbackAttachment("a", "not base64!"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void refused(ThrowingCallable call, String message) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            AccountFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(422);
              assertThat(failure.code()).isEqualTo("FEEDBACK_INVALID");
              assertThat(failure).hasMessage(message);
            });
  }
}
