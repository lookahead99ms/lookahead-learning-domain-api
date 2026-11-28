package com.lookahead.domain.validator;

import com.lookahead.domain.client.SupportEmailClient.Image;
import com.lookahead.domain.dto.SupportFeedback;
import com.lookahead.domain.dto.SupportFeedbackAttachment;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.util.SupportFeedbackRules;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Checks a support request: its request key, its type, subject, message and reply address, and its
 * images. DLV-1016: these checks were the first lines of SupportFeedbackService.submit and moved
 * here word for word and in the same order; every refusal is 422 {@code FEEDBACK_INVALID} with the
 * sentence it had.
 */
@Component
public class SupportFeedbackValidator {
  /**
   * The checked images as the PNG files that are sent, and the bytes each was decoded from (the
   * service fingerprints those).
   */
  public record Images(List<Image> images, List<byte[]> decoded) {}

  /**
   * The key and the text fields; answers the attachments to check next (none when the request has
   * no images).
   */
  public List<SupportFeedbackAttachment> fields(String key, SupportFeedback feedback) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{16,80}"))
      invalid("A valid request key is required");
    if (feedback == null
        || feedback.type() == null
        || !Set.of("feedback", "problem", "question").contains(feedback.type()))
      invalid("Choose a feedback type");
    text(feedback.subject(), 160);
    text(feedback.message(), 5000);
    if (feedback.subject().contains("\r")
        || feedback.subject().contains("\n")
        || !SupportFeedbackRules.email(feedback.replyTo()))
      invalid("Check the subject and reply email");
    List<SupportFeedbackAttachment> attachments =
        feedback.images() == null ? List.of() : feedback.images();
    if (attachments.size() > 3) invalid("Attach at most three PNG or JPEG images");
    return attachments;
  }

  /**
   * At most 2 MB each and 5 MB together, PNG or JPEG, at most 16 million pixels; each is written
   * again as PNG. An image that cannot be decoded at all fails with the decoder's own exception,
   * which the service answers as before.
   */
  public Images images(List<SupportFeedbackAttachment> attachments) throws IOException {
    List<Image> images = new ArrayList<>();
    int total = 0;
    List<byte[]> decoded = new ArrayList<>();
    for (SupportFeedbackAttachment attachment : attachments) {
      if (attachment == null || attachment.data() == null || attachment.data().length() > 2_800_000)
        invalid("Each image must be at most 2 MB");
      byte[] raw = Base64.getDecoder().decode(attachment.data());
      total += raw.length;
      if (raw.length > 2 * 1024 * 1024 || total > 5 * 1024 * 1024)
        invalid("Images exceed the 5 MB total limit");
      decoded.add(raw);
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) invalid("Only PNG and JPEG images are supported");
        var reader = readers.next();
        try {
          reader.setInput(input);
          String format = reader.getFormatName().toLowerCase(Locale.ROOT);
          if (!Set.of("png", "jpeg", "jpg").contains(format)
              || (long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000)
            invalid("Unsupported image format or dimensions");
          var image = reader.read(0);
          var normalized = new ByteArrayOutputStream();
          ImageIO.write(image, "png", normalized);
          if (normalized.size() > 8 * 1024 * 1024)
            invalid("Image is too large after safe conversion");
          images.add(
              new Image("screenshot-" + (images.size() + 1) + ".png", normalized.toByteArray()));
        } finally {
          reader.dispose();
        }
      }
    }
    return new Images(images, decoded);
  }

  private static void text(String value, int limit) {
    if (value == null || value.isBlank() || value.length() > limit)
      invalid("Message fields exceed their limits");
  }

  private static void invalid(String message) {
    throw new AccountFailure(422, "FEEDBACK_INVALID", message);
  }
}
