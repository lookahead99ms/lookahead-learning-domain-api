package com.lookahead.domain.service.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.lookahead.domain.client.SupportEmailSmtpClient;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit;
import com.lookahead.domain.db.store.review.SupportReceipt;
import com.lookahead.domain.dto.SupportFeedback;
import com.lookahead.domain.dto.SupportFeedbackAttachment;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.PlatformReviewModels;
import com.lookahead.domain.model.SupportFeedbackModels;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.util.SupportFeedbackRules;
import com.lookahead.domain.validator.SupportFeedbackValidator;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import tools.jackson.databind.json.JsonMapper;

class SupportFeedbackServiceTest {
  private static class TestReceiptStore implements SupportReceipt {
    private final Map<String, Reservation> stored = new HashMap<>();
    private final Map<String, String> digests = new HashMap<>();
    int released;

    public Reservation reserve(
        String owner, String key, String digest, int perWindow, Duration window) {
      String id = owner + key;
      if (stored.containsKey(id)) {
        if (!digest.equals(digests.get(id))) return Reservation.refused(Refused.KEY_REUSED);
        return new Reservation(stored.get(id).receipt(), false);
      }
      var value =
          new Reservation(
              new SupportFeedbackModels.Receipt(UUID.randomUUID().toString(), "unconfirmed"), true);
      stored.put(id, value);
      digests.put(id, digest);
      return value;
    }

    public void accepted(String owner, String key) {
      var old = stored.get(owner + key);
      stored.put(
          owner + key,
          new Reservation(
              new SupportFeedbackModels.Receipt(old.receipt().reference(), "accepted"), false));
    }

    public void release(String owner, String key) {
      if ("unconfirmed".equals(stored.get(owner + key).receipt().status())) {
        stored.remove(owner + key);
        digests.remove(owner + key);
        released++;
      }
    }
  }

  @TempDir Path root;
  final AccountPrincipal owner =
      new AccountPrincipal(UUID.randomUUID(), "author@lookahead.test", "Owner", true);
  final AccountPrincipal learner =
      new AccountPrincipal(UUID.randomUUID(), "learner@example.test", "A Learner", true);
  PlatformReviewService reviews;
  JavaMailSender sender;
  ObjectProvider<JavaMailSender> provider;
  MimeMessage mime;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setup() {
    var author = mock(AuthorPreviewLocalService.class);
    when(author.allowed(owner)).thenReturn(true);
    reviews = PlatformReviewFixtures.service(root, author, JsonMapper.builder().build(), "");
    sender = mock(JavaMailSender.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    provider = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    when(provider.getIfAvailable()).thenReturn(sender);
    mime = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(mime);
  }

  SupportFeedbackService service(
      String inbox, SupportReceipt store, PlatformReviewService reviews) {
    return new SupportFeedbackService(
        new SupportEmailSmtpClient(provider, "sender@example.test", inbox),
        store,
        reviews,
        new SupportFeedbackValidator());
  }

  SupportFeedbackService service(SupportReceipt store) {
    return service("support@example.test", store, reviews);
  }

  static SupportFeedback feedback(String type, String subject) {
    return new SupportFeedback(type, subject, "A useful message", "reply@example.test", List.of());
  }

  List<PlatformReviewModels.Summary> recorded() {
    return reviews.list(owner, null).items();
  }

  /**
   * DLV-971: the request is recorded once for Team Reviews with its kind and receipt reference,
   * then emailed once.
   */
  @Test
  void acceptsOnceAndReplaysWithoutSendingTwice() throws Exception {
    var service = service(new TestReceiptStore());
    var feedback = feedback("feedback", "A clear subject");
    var first = service.submit(learner, "request-1234567890", feedback);
    assertThat(first.status()).isEqualTo("accepted");
    assertThat(service.submit(learner, "request-1234567890", feedback)).isEqualTo(first);
    verify(sender, times(1)).send(mime);
    assertThat(mime.getReplyTo()[0].toString()).isEqualTo("reply@example.test");
    assertThat(mime.getContent().toString()).contains("A useful message");
    assertThatThrownBy(() ->
            service.submit(
                learner,
                "request-1234567890",
                new SupportFeedback(
                    "feedback", "Changed", "A useful message", "reply@example.test", List.of())))
        .hasMessageContaining("same message");
    assertThat(recorded()).hasSize(1);
    var item = reviews.item(owner, recorded().getFirst().id());
    assertThat(item.kind()).isEqualTo("request");
    assertThat(item.requestType()).isEqualTo("feedback");
    assertThat(item.reference()).isEqualTo(first.reference());
    assertThat(item.category()).isEqualTo("idea");
    assertThat(item.page()).isEqualTo("Support");
    assertThat(item.pageUrl()).isNull();
    assertThat(item.title()).isEqualTo("A clear subject");
    assertThat(item.details()).isEqualTo("A useful message");
    assertThat(item.priority()).isEqualTo("medium");
    assertThat(item.status()).isEqualTo("new");
    assertThat(item.submitterRole()).isEqualTo("user");
    assertThat(item.reviewerName()).isEqualTo("A Learner");
    assertThat(item.attachments()).isEmpty();
    // The reply address is in the email and the receipt row only.
    // DLV-971: the request is one record under support-requests/records/YYYY/MM/DD/SUP-000001.json,
    // never in the review store.
    try (var files = Files.walk(root.resolve("support-requests/records"))) {
      var record = files.filter(Files::isRegularFile).findFirst().orElseThrow();
      assertThat(record.getFileName().toString()).isEqualTo("SUP-000001.json");
      assertThat(Files.readString(record))
          .doesNotContain("reply@example.test")
          .contains("\"kind\" : \"request\"");
    }
    assertThat(Files.exists(root.resolve("reviews/records"))).isFalse();
  }

  @Test
  void unconfirmedSendCannotBeDuplicatedByRetry() {
    doThrow(new MailSendException("synthetic failure")).when(sender).send(any(MimeMessage.class));
    var service = service(new TestReceiptStore());
    var feedback = feedback("problem", "A subject");
    var receipt = service.submit(learner, "request-1234567890", feedback);
    assertThat(receipt.status()).isEqualTo("unconfirmed");
    assertThat(service.submit(learner, "request-1234567890", feedback)).isEqualTo(receipt);
    verify(sender, times(1)).send(any(MimeMessage.class));
    assertThat(recorded()).hasSize(1);
    assertThat(recorded().getFirst().requestType()).isEqualTo("problem");
    assertThat(recorded().getFirst().category()).isEqualTo("problem");
  }

  /**
   * DLV-971 (owner, 2026-11-06): no mail sender, but the request is recorded for Team Reviews:
   * "recorded", not 503.
   */
  @Test
  void withoutMailTheRequestIsStillRecorded() {
    when(provider.getIfAvailable()).thenReturn(null);
    var store = new TestReceiptStore();
    var service = service(store);
    try (var log = LogCapture.start()) {
      var receipt =
          service.submit(
              learner, "request-1234567890", feedback("question", "How do plans renew?"));
      assertThat(receipt.status()).isEqualTo("recorded");
      assertThat(recorded()).hasSize(1);
      assertThat(recorded().getFirst().reference()).isEqualTo(receipt.reference());
      assertThat(recorded().getFirst().requestType()).isEqualTo("question");
      var line = log.only("support.send");
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Support request "
                  + receipt.reference()
                  + " was recorded for Team Reviews but its email was not sent: spring.mail.host"
                  + " (no mail sender is configured) is missing or not an email address");
      assertThat(LogCapture.keys(line))
          .containsEntry("reason", "EMAIL_NOT_SENT")
          .containsEntry("support.reference", receipt.reference());
      // A retry with the same key records nothing new and sends nothing.
      service.submit(learner, "request-1234567890", feedback("question", "How do plans renew?"));
      assertThat(recorded()).hasSize(1);
      assertThat(store.released).isZero();
    }
    verifyNoInteractions(sender);
  }

  /** Neither the review store nor mail: 503 with the key released, so the next try starts over. */
  @Test
  void withoutMailAndWithoutTheStoreTheRequestIsRefusedAndTheKeyFreed() {
    when(provider.getIfAvailable()).thenReturn(null);
    var store = new TestReceiptStore();
    var broken = mock(PlatformReviewService.class);
    when(broken.recordRequest(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new AccountFailure(
                503, "PLATFORM_REVIEW_STORAGE_UNAVAILABLE", "Review storage is unavailable."));
    try (var log = LogCapture.start()) {
      var refused =
          catchThrowableOfType(
              AccountFailure.class,
              () ->
                  service("support@example.test", store, broken)
                      .submit(learner, "request-1234567890", feedback("problem", "Broken")));
      assertThat(refused.status()).isEqualTo(503);
      assertThat(refused.code()).isEqualTo("SUPPORT_UNAVAILABLE");
      assertThat(refused.getMessage()).contains("not recorded or sent");
      assertThat(store.released).isEqualTo(1);
      assertThat(log.lines())
          .extracting(line -> LogCapture.keys(line).get("reason"))
          .containsExactly("REQUEST_NOT_RECORDED", "SUPPORT_UNAVAILABLE");
      assertThat(log.lines().getFirst().getFormattedMessage())
          .endsWith(
              "was not recorded for Team Reviews (AccountFailure,"
                  + " PLATFORM_REVIEW_STORAGE_UNAVAILABLE); it is still sent by email");
      assertThat(log.lines().getLast().getFormattedMessage())
          .isEqualTo(
              "Support message refused with 503: spring.mail.host (no mail sender is configured) is"
                  + " missing or not an email address, and the request could not be recorded for"
                  + " Team Reviews");
      log.containsNone("Broken", "reply@example.test");
    }
    // The key is free again: a working store and mail now record and send it.
    when(provider.getIfAvailable()).thenReturn(sender);
    assertThat(
            service(store)
                .submit(learner, "request-1234567890", feedback("problem", "Broken"))
                .status())
        .isEqualTo("accepted");
    assertThat(recorded()).hasSize(1);
  }

  /** The review store fails but mail works: the email goes as before and the failure is logged. */
  @Test
  void aStoreFailureStillSendsTheEmail() {
    var broken = mock(PlatformReviewService.class);
    when(broken.recordRequest(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("disk full at /private/reviews"));
    var store = new TestReceiptStore();
    try (var log = LogCapture.start()) {
      var receipt =
          service("support@example.test", store, broken)
              .submit(learner, "request-1234567890", feedback("feedback", "Idea"));
      assertThat(receipt.status()).isEqualTo("accepted");
      var line = log.only("support.record");
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Support request "
                  + receipt.reference()
                  + " was not recorded for Team Reviews (IllegalStateException); it is still sent"
                  + " by email");
      assertThat(LogCapture.keys(line))
          .containsEntry("reason", "REQUEST_NOT_RECORDED")
          .containsEntry("error.type", "IllegalStateException");
      log.containsNone("disk full", "Idea");
    }
    verify(sender, times(1)).send(mime);
    assertThat(store.released).isZero();
    assertThat(recorded()).isEmpty();
  }

  /**
   * DLV-969 (owner, 2026-11-04): an email that did not go, a missing setting and an unreadable
   * upload are explained.
   */
  @Test
  void failuresAreLoggedWithoutAddressesOrMessages() {
    doThrow(new MailSendException("smtp.internal refused reply@example.test"))
        .when(sender)
        .send(any(MimeMessage.class));
    var feedback =
        new SupportFeedback(
            "problem", "A subject", "A private message", "reply@example.test", List.of());
    var broken = mock(PlatformReviewService.class);
    when(broken.recordRequest(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new AccountFailure(
                503, "PLATFORM_REVIEW_STORAGE_UNAVAILABLE", "Review storage is unavailable."));
    try (var log = LogCapture.start()) {
      var receipt = service(new TestReceiptStore()).submit(learner, "request-1234567890", feedback);
      assertThatThrownBy(() ->
              service("", new TestReceiptStore(), broken)
                  .submit(learner, "request-1234567890", feedback))
          .hasMessageContaining("not available");
      var unreadable =
          new SupportFeedback(
              "problem",
              "A subject",
              "A message",
              "reply@example.test",
              List.of(new SupportFeedbackAttachment("x.png", "%%% not base64")));
      assertThatThrownBy(() ->
              service(new TestReceiptStore()).submit(learner, "request-1234567890", unreadable))
          .hasMessageContaining("PNG or JPEG");
      assertThat(log.lines("support.send"))
          .extracting(ILoggingEvent::getFormattedMessage)
          .containsExactly(
              "Support message "
                  + receipt.reference()
                  + " was saved but its email was not sent (MailSendException); the receipt stays"
                  + " unconfirmed",
              "Support message refused with 503: app.support.inbox is missing or not an email"
                  + " address, and the request could not be recorded for Team Reviews",
              "Support message refused with 422: its fields or images could not be read"
                  + " (IllegalArgumentException)");
      assertThat(log.lines())
          .extracting(line -> LogCapture.keys(line).get("reason"))
          .containsExactly(
              null,
              "EMAIL_NOT_SENT",
              "REQUEST_NOT_RECORDED",
              "SUPPORT_UNAVAILABLE",
              "FEEDBACK_INVALID");
      log.containsNone(
          "reply@example.test",
          "sender@example.test",
          "support@example.test",
          "smtp.internal",
          "A private message");
    }
  }

  /**
   * DLV-970: a DynamoDB outage, an unanswered read or a lost owner fence in the receipt store is
   * never a 422; it reaches AccountErrorHandler unchanged, which answers 503
   * ACCOUNT_STORAGE_UNAVAILABLE (Unavailable, SDK failures) or 409 (Conflict).
   */
  /**
   * DLV-1016: the store answers that nothing was reserved; the service makes the two refusals, and
   * nothing is recorded, sent, accepted or released.
   */
  @Test
  void aKeyCarryingAnotherRequestAndAUsedUpQuotaAreRefusedByTheService() {
    var cases =
        List.of(
            new Object[] {
              SupportReceipt.Refused.KEY_REUSED,
              409,
              "FEEDBACK_KEY_REUSED",
              "Use the same message when checking an existing submission"
            },
            new Object[] {
              SupportReceipt.Refused.QUOTA_REACHED,
              429,
              "FEEDBACK_RATE_LIMIT",
              "Please wait before sending another message"
            });
    for (var expected : cases) {
      var store = mock(SupportReceipt.class, withSettings().mockMaker(MockMakers.PROXY));
      when(store.reserve(any(), any(), any(), anyInt(), any()))
          .thenReturn(SupportReceipt.Reservation.refused((SupportReceipt.Refused) expected[0]));
      var refused =
          catchThrowableOfType(
              AccountFailure.class,
              () ->
                  service(store)
                      .submit(learner, "request-1234567890", feedback("feedback", "A subject")));
      assertThat(refused.status()).isEqualTo(expected[1]);
      assertThat(refused.code()).isEqualTo(expected[2]);
      assertThat(refused.getMessage()).isEqualTo(expected[3]);
      verify(store)
          .reserve(
              eq(learner.accountId().toString()),
              eq("request-1234567890"),
              any(),
              eq(SupportFeedbackRules.PER_WINDOW),
              eq(SupportFeedbackRules.WINDOW));
      verify(store, never()).release(any(), any());
      verify(store, never()).accepted(any(), any());
    }
    verifyNoInteractions(sender);
    assertThat(recorded()).isEmpty();
  }

  @Test
  void storeOutagesAndConflictsPassThroughUnchanged() {
    var sdkDown = SdkClientException.create("down");
    var cancelled =
        TransactionCanceledException.builder()
            .cancellationReasons(
                CancellationReason.builder().code("ConditionalCheckFailed").build())
            .build();
    var readDown =
        catchThrowable(() ->
            OwnerTransactionReads.read(() -> {
              throw sdkDown;
            }));
    assertThat(readDown).isInstanceOf(OwnerTransactionUnit.OwnerReadUnavailable.class);
    var outcomes = List.of(storeFailure(sdkDown), storeFailure(cancelled), readDown);
    assertThat(outcomes.get(0)).isInstanceOf(DynamoWrites.Unavailable.class);
    assertThat(outcomes.get(1)).isInstanceOf(DynamoWrites.Conflict.class);
    for (var failure : outcomes) {
      var store = mock(SupportReceipt.class, withSettings().mockMaker(MockMakers.PROXY));
      when(store.reserve(any(), any(), any(), anyInt(), any())).thenThrow(failure);
      assertThatThrownBy(() ->
              service(store)
                  .submit(learner, "request-1234567890", feedback("feedback", "A subject")))
          .isSameAs(failure);
      verify(store, never()).release(any(), any());
    }
    verifyNoInteractions(sender);
    assertThat(recorded()).isEmpty();
  }

  /**
   * The store's own exception types have package-private constructors: a transaction against a
   * failing client makes real ones.
   */
  static Throwable storeFailure(RuntimeException sdkFailure) {
    var client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(sdkFailure);
    return catchThrowable(
        () -> DynamoWrites.transaction(client).insertOnly("t", DynamoItems.key("a", "b")).commit());
  }

  @Test
  void rejectsHeaderInjectionAndNonImagesBeforeSending() {
    var service = service(new TestReceiptStore());
    assertThatThrownBy(() ->
            service.submit(
                learner,
                "request-1234567890",
                new SupportFeedback(
                    "feedback",
                    "subject\r\nBcc: other@example.test",
                    "message",
                    "reply@example.test",
                    List.of())))
        .hasMessageContaining("subject");
    assertThatThrownBy(() ->
            service.submit(
                learner,
                "request-1234567890",
                new SupportFeedback(
                    "feedback",
                    "subject",
                    "message",
                    "reply@example.test",
                    List.of(
                        new SupportFeedbackAttachment(
                            "image.png",
                            Base64.getEncoder().encodeToString("not an image".getBytes()))))))
        .hasMessageContaining("PNG");
    assertThatThrownBy(
            () -> service.submit(null, "request-1234567890", feedback("feedback", "subject")))
        .hasMessageContaining("Sign in");
    assertThatThrownBy(() ->
            service.submit(
                new AccountPrincipal(UUID.randomUUID(), "off@example.test", "Off", false),
                "request-1234567890",
                feedback("feedback", "subject")))
        .hasMessageContaining("Sign in");
    verifyNoInteractions(provider);
    assertThat(recorded()).isEmpty();
  }

  @Test
  void decodesAndDeliversImagesWithGeneratedSafeNames() throws Exception {
    var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    pixels.setRGB(0, 0, 0x336699);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(pixels, "png", bytes);
    var service = service(new TestReceiptStore());
    var feedback =
        new SupportFeedback(
            "problem",
            "Screenshot",
            "Reproduction steps",
            "reply@example.test",
            List.of(
                new SupportFeedbackAttachment(
                    "../../untrusted.png",
                    Base64.getEncoder().encodeToString(bytes.toByteArray()))));
    assertThat(service.submit(learner, "request-1234567890", feedback).status())
        .isEqualTo("accepted");
    mime.saveChanges();
    var root = (Multipart) mime.getContent();
    assertThat(root.getCount()).isEqualTo(2);
    var attachment = root.getBodyPart(1);
    assertThat(attachment.getFileName()).isEqualTo("screenshot-1.png");
    assertThat(attachment.getContentType()).startsWith("image/png");
    var decoded = ImageIO.read(attachment.getInputStream());
    assertThat(decoded.getWidth()).isEqualTo(2);
    assertThat(decoded.getRGB(0, 0) & 0xffffff).isEqualTo(0x336699);
    verify(sender).send(mime);
    // DLV-971: the same screenshot is stored on the Team Reviews item through the review image
    // pipeline.
    var item = reviews.item(owner, recorded().getFirst().id());
    assertThat(item.attachments()).hasSize(1);
    assertThat(item.attachments().getFirst().name()).isEqualTo("screenshot-1.png");
    assertThat(item.attachments().getFirst().contentType()).isEqualTo("image/png");
    assertThat(
            ImageIO.read(
                        new ByteArrayInputStream(
                            reviews
                                .attachment(owner, item.id(), item.attachments().getFirst().id())
                                .bytes()))
                    .getRGB(0, 0)
                & 0xffffff)
        .isEqualTo(0x336699);
  }
}
