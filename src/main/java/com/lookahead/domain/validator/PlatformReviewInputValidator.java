package com.lookahead.domain.validator;

import static com.lookahead.domain.model.PlatformReviewModels.*;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks on what a person sends for a platform review item or a support request: the fields,
 * the free text, the image count and sizes, the file names and the page date. DLV-1016: these were
 * private parts of PlatformReviewService, moved here word for word; the service keeps the workflow
 * (who may do what, and what is stored).
 */
public final class PlatformReviewInputValidator {
  public static final int TITLE_LENGTH = 120,
      DETAILS_LENGTH = 5000,
      NOTE_LENGTH = 2000,
      PAGE_URL_LENGTH = 500;
  public static final int IMAGES_PER_ITEM = 6,
      IMAGE_BYTES = 5 * 1024 * 1024,
      ITEM_IMAGE_BYTES = 20 * 1024 * 1024;

  private PlatformReviewInputValidator() {}

  /** One accepted upload: its cleaned file name and its checked, metadata-free image. */
  public record Prepared(String name, Image image) {}

  public static List<Prepared> images(List<Upload> uploads, List<StoredAttachment> kept) {
    var list = uploads == null ? List.<Upload>of() : uploads;
    if (kept.size() + list.size() > IMAGES_PER_ITEM) throw tooManyImages();
    long total = kept.stream().mapToLong(StoredAttachment::size).sum();
    var images = new ArrayList<Prepared>();
    for (Upload upload : list) {
      if (upload.bytes().length > IMAGE_BYTES) throw imageTooLarge();
      if (upload.bytes().length == 0) throw invalid("An image is empty.");
      Image image;
      try {
        image = PlatformReviewImageValidator.clean(upload.declaredType(), upload.bytes());
      } catch (IllegalArgumentException rejected) {
        throw invalid(rejected.getMessage());
      }
      total += upload.bytes().length;
      images.add(new Prepared(name(upload.name()), image));
    }
    if (total > ITEM_IMAGE_BYTES)
      throw imageLimit(
          "platform-review-item-image-bytes",
          ITEM_IMAGE_BYTES,
          tooLarge("Images on one item must total 20 MB or less."));
    return images;
  }

  public static String name(String name) {
    String clean =
        name == null
            ? ""
            : name.codePoints()
                .filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
    if (clean.isEmpty()) clean = "image";
    return clean.length() > 200 ? clean.substring(0, 200) : clean;
  }

  public static Fields fields(Fields fields) {
    if (fields == null) throw invalid("The item is incomplete.");
    if (fields.category() == null || !CATEGORIES.contains(fields.category()))
      throw invalid("Choose a type.");
    String page = currentPage(fields.page());
    if (page == null || !PAGES.contains(page)) throw invalid("Choose a page.");
    if (fields.priority() == null || !PRIORITIES.contains(fields.priority()))
      throw invalid("Choose how important this is.");
    String title =
        text(fields.title(), TITLE_LENGTH, false, "Keep the title within 120 characters.").strip();
    if (title.isEmpty()) throw invalid("Add a short title.");
    String details =
        text(
            fields.details(), DETAILS_LENGTH, true, "Keep the description within 5000 characters.");
    if (details.isBlank()) throw invalid("Describe what you saw and what should change.");
    String pageUrl =
        text(
                fields.pageUrl() == null ? "" : fields.pageUrl(),
                PAGE_URL_LENGTH,
                false,
                "The page address is too long.")
            .strip();
    if (!pageUrl.isEmpty() && (!pageUrl.matches("/(?!/)\\S*") || pageUrl.indexOf('\\') >= 0))
      throw invalid(
          "The page address must start with / and be on this site, for example /learn/core-java.");
    return new Fields(fields.category(), page, pageUrl, title, details, fields.priority());
  }

  public static String text(String value, int max, boolean multiline, String tooLong) {
    if (value == null) throw invalid("The item is incomplete.");
    if (value.length() > max) throw invalid(tooLong);
    if (value
        .codePoints()
        .anyMatch(c ->
            (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t' || c == '\r')))
                || (c >= 0xd800 && c <= 0xdfff)))
      throw invalid("Remove unsupported characters from the text.");
    return value;
  }

  public static LocalDate day(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException bad) {
      throw invalid("Invalid date.");
    }
  }

  /**
   * More than six images on one item: the limit line, then 422. The request validator refuses the
   * same way before the upload is read.
   */
  static AccountFailure tooManyImages() {
    return imageLimit(
        "platform-review-images", IMAGES_PER_ITEM, invalid("Attach at most 6 images to one item."));
  }

  /** One image over 5 MB: the limit line, then 413. */
  static AccountFailure imageTooLarge() {
    return imageLimit(
        "platform-review-image-bytes",
        IMAGE_BYTES,
        tooLarge("Each image must be 5 MB or smaller."));
  }

  /**
   * An image count or size limit hit: one {@code lookahead.limit} line naming the limit, then the
   * caller's refusal.
   */
  private static AccountFailure imageLimit(String name, long max, AccountFailure refusal) {
    EventLog.limit("platform-review.images", refusal.code(), name, max, "per item")
        .addKeyValue("http.status", refusal.status())
        .log(
            "Platform review image limit hit: {} (at most {}); refused with {}",
            name,
            max,
            refusal.status());
    return refusal;
  }

  public static AccountFailure invalid(String message) {
    return new AccountFailure(422, "PLATFORM_REVIEW_INVALID", message);
  }

  public static AccountFailure tooLarge(String message) {
    return new AccountFailure(413, "PLATFORM_REVIEW_TOO_LARGE", message);
  }
}
