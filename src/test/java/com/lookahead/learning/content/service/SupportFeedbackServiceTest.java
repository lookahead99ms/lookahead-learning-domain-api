package com.lookahead.learning.content.service;
import com.lookahead.domain.review.platform.FileSystemPlatformReviewObjects;
import com.lookahead.domain.review.platform.PlatformReviewModels;
import com.lookahead.domain.review.platform.PlatformReviewService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Path;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class SupportFeedbackServiceTest {
  private static class TestReceiptStore implements SupportReceiptStore {
    private final Map<String,Reservation> stored=new HashMap<>(); private final Map<String,String> digests=new HashMap<>();
    int released;
    public Reservation reserve(String owner,String key,String digest) {
      String id=owner+key;
      if(stored.containsKey(id)) {
        if(!digest.equals(digests.get(id))) throw new com.lookahead.learning.content.exception.AccountFailure(409,"FEEDBACK_KEY_REUSED","Use the same message when checking an existing submission");
        return new Reservation(stored.get(id).receipt(),false);
      }
      var value=new Reservation(new SupportFeedbackService.Receipt(UUID.randomUUID().toString(),"unconfirmed"),true);
      stored.put(id,value);digests.put(id,digest);return value;
    }
    public void accepted(String owner,String key) { var old=stored.get(owner+key);stored.put(owner+key,new Reservation(new SupportFeedbackService.Receipt(old.receipt().reference(),"accepted"),false)); }
    public void release(String owner,String key) { if("unconfirmed".equals(stored.get(owner+key).receipt().status())){stored.remove(owner+key);digests.remove(owner+key);released++;} }
  }
  @TempDir Path root;
  final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"author@lookahead.test","Owner",true);
  final AccountPrincipal learner=new AccountPrincipal(UUID.randomUUID(),"learner@example.test","A Learner",true);
  PlatformReviewService reviews;
  JavaMailSender sender; ObjectProvider<JavaMailSender> provider; MimeMessage mime;

  @SuppressWarnings("unchecked")
  @BeforeEach void setup() {
    var author=mock(LocalAuthorAccess.class); when(author.allowed(owner)).thenReturn(true);
    reviews=new PlatformReviewService(new FileSystemPlatformReviewObjects(root),author,tools.jackson.databind.json.JsonMapper.builder().build(),"");
    sender=mock(JavaMailSender.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    provider=mock(ObjectProvider.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    when(provider.getIfAvailable()).thenReturn(sender);
    mime=new MimeMessage(Session.getInstance(new Properties())); when(sender.createMimeMessage()).thenReturn(mime);
  }
  SupportFeedbackService service(String inbox,SupportReceiptStore store,PlatformReviewService reviews) { return new SupportFeedbackService(provider,"sender@example.test",inbox,store,reviews); }
  SupportFeedbackService service(SupportReceiptStore store) { return service("support@example.test",store,reviews); }
  static SupportFeedbackService.Feedback feedback(String type,String subject) { return new SupportFeedbackService.Feedback(type,subject,"A useful message","reply@example.test",List.of()); }
  List<PlatformReviewModels.Summary> recorded() { return reviews.list(owner,null).items(); }

  /** DLV-971: the request is recorded once for Team Reviews with its kind and receipt reference, then emailed once. */
  @Test void acceptsOnceAndReplaysWithoutSendingTwice() throws Exception {
    var service=service(new TestReceiptStore());
    var feedback=feedback("feedback","A clear subject");
    var first=service.submit(learner,"request-1234567890",feedback);
    assertThat(first.status()).isEqualTo("accepted");
    assertThat(service.submit(learner,"request-1234567890",feedback)).isEqualTo(first);
    verify(sender,times(1)).send(mime);
    assertThat(mime.getReplyTo()[0].toString()).isEqualTo("reply@example.test");
    assertThat(mime.getContent().toString()).contains("A useful message");
    assertThatThrownBy(()->service.submit(learner,"request-1234567890",new SupportFeedbackService.Feedback("feedback","Changed","A useful message","reply@example.test",List.of()))).hasMessageContaining("same message");
    assertThat(recorded()).hasSize(1);
    var item=reviews.item(owner,recorded().getFirst().id());
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
    assertThat(java.nio.file.Files.readString(root.resolve("reviews").resolve(java.nio.file.Files.list(root.resolve("reviews")).findFirst().orElseThrow().getFileName()))).doesNotContain("reply@example.test");
  }
  @Test void unconfirmedSendCannotBeDuplicatedByRetry() {
    doThrow(new org.springframework.mail.MailSendException("synthetic failure")).when(sender).send(any(MimeMessage.class));
    var service=service(new TestReceiptStore());
    var feedback=feedback("problem","A subject");
    var receipt=service.submit(learner,"request-1234567890",feedback);
    assertThat(receipt.status()).isEqualTo("unconfirmed");
    assertThat(service.submit(learner,"request-1234567890",feedback)).isEqualTo(receipt);
    verify(sender,times(1)).send(any(MimeMessage.class));
    assertThat(recorded()).hasSize(1);
    assertThat(recorded().getFirst().requestType()).isEqualTo("problem");
    assertThat(recorded().getFirst().category()).isEqualTo("problem");
  }
  /** DLV-971 (owner, 2026-11-06): no mail sender, but the request is recorded for Team Reviews: "recorded", not 503. */
  @Test void withoutMailTheRequestIsStillRecorded() {
    when(provider.getIfAvailable()).thenReturn(null);
    var store=new TestReceiptStore();
    var service=service(store);
    try(var log=com.lookahead.domain.LogCapture.start()){
      var receipt=service.submit(learner,"request-1234567890",feedback("question","How do plans renew?"));
      assertThat(receipt.status()).isEqualTo("recorded");
      assertThat(recorded()).hasSize(1);
      assertThat(recorded().getFirst().reference()).isEqualTo(receipt.reference());
      assertThat(recorded().getFirst().requestType()).isEqualTo("question");
      var line=log.only("support.send");
      assertThat(line.getFormattedMessage()).isEqualTo("Support request "+receipt.reference()+" was recorded for Team Reviews but its email was not sent: spring.mail.host (no mail sender is configured) is missing or not an email address");
      assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("reason","EMAIL_NOT_SENT").containsEntry("support.reference",receipt.reference());
      // A retry with the same key records nothing new and sends nothing.
      service.submit(learner,"request-1234567890",feedback("question","How do plans renew?"));
      assertThat(recorded()).hasSize(1);
      assertThat(store.released).isZero();
    }
    verifyNoInteractions(sender);
  }
  /** Neither the review store nor mail: 503 with the key released, so the next try starts over. */
  @Test void withoutMailAndWithoutTheStoreTheRequestIsRefusedAndTheKeyFreed() {
    when(provider.getIfAvailable()).thenReturn(null);
    var store=new TestReceiptStore();
    var broken=mock(PlatformReviewService.class);
    when(broken.recordRequest(any(),any(),any(),any(),any(),any())).thenThrow(new AccountFailure(503,"PLATFORM_REVIEW_STORAGE_UNAVAILABLE","Review storage is unavailable."));
    try(var log=com.lookahead.domain.LogCapture.start()){
      var refused=catchThrowableOfType(AccountFailure.class,()->service("support@example.test",store,broken).submit(learner,"request-1234567890",feedback("problem","Broken")));
      assertThat(refused.status()).isEqualTo(503);
      assertThat(refused.code()).isEqualTo("SUPPORT_UNAVAILABLE");
      assertThat(refused.getMessage()).contains("not recorded or sent");
      assertThat(store.released).isEqualTo(1);
      assertThat(log.lines()).extracting(line->com.lookahead.domain.LogCapture.keys(line).get("reason")).containsExactly("REQUEST_NOT_RECORDED","SUPPORT_UNAVAILABLE");
      assertThat(log.lines().getFirst().getFormattedMessage()).endsWith("was not recorded for Team Reviews (AccountFailure, PLATFORM_REVIEW_STORAGE_UNAVAILABLE); it is still sent by email");
      assertThat(log.lines().getLast().getFormattedMessage()).isEqualTo("Support message refused with 503: spring.mail.host (no mail sender is configured) is missing or not an email address, and the request could not be recorded for Team Reviews");
      log.containsNone("Broken","reply@example.test");
    }
    // The key is free again: a working store and mail now record and send it.
    when(provider.getIfAvailable()).thenReturn(sender);
    assertThat(service(store).submit(learner,"request-1234567890",feedback("problem","Broken")).status()).isEqualTo("accepted");
    assertThat(recorded()).hasSize(1);
  }
  /** The review store fails but mail works: the email goes as before and the failure is logged. */
  @Test void aStoreFailureStillSendsTheEmail() {
    var broken=mock(PlatformReviewService.class);
    when(broken.recordRequest(any(),any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("disk full at /private/reviews"));
    var store=new TestReceiptStore();
    try(var log=com.lookahead.domain.LogCapture.start()){
      var receipt=service("support@example.test",store,broken).submit(learner,"request-1234567890",feedback("feedback","Idea"));
      assertThat(receipt.status()).isEqualTo("accepted");
      var line=log.only("support.record");
      assertThat(line.getFormattedMessage()).isEqualTo("Support request "+receipt.reference()+" was not recorded for Team Reviews (IllegalStateException); it is still sent by email");
      assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("reason","REQUEST_NOT_RECORDED").containsEntry("error.type","IllegalStateException");
      log.containsNone("disk full","Idea");
    }
    verify(sender,times(1)).send(mime);
    assertThat(store.released).isZero();
    assertThat(recorded()).isEmpty();
  }
  /** DLV-969 (owner, 2026-11-04): an email that did not go, a missing setting and an unreadable upload are explained. */
  @Test void failuresAreLoggedWithoutAddressesOrMessages() {
    doThrow(new org.springframework.mail.MailSendException("smtp.internal refused reply@example.test")).when(sender).send(any(MimeMessage.class));
    var feedback=new SupportFeedbackService.Feedback("problem","A subject","A private message","reply@example.test",List.of());
    var broken=mock(PlatformReviewService.class);
    when(broken.recordRequest(any(),any(),any(),any(),any(),any())).thenThrow(new AccountFailure(503,"PLATFORM_REVIEW_STORAGE_UNAVAILABLE","Review storage is unavailable."));
    try(var log=com.lookahead.domain.LogCapture.start()){
      var receipt=service(new TestReceiptStore()).submit(learner,"request-1234567890",feedback);
      assertThatThrownBy(()->service("",new TestReceiptStore(),broken).submit(learner,"request-1234567890",feedback))
        .hasMessageContaining("not available");
      var unreadable=new SupportFeedbackService.Feedback("problem","A subject","A message","reply@example.test",List.of(new SupportFeedbackService.Attachment("x.png","%%% not base64")));
      assertThatThrownBy(()->service(new TestReceiptStore()).submit(learner,"request-1234567890",unreadable))
        .hasMessageContaining("PNG or JPEG");
      assertThat(log.lines("support.send")).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).containsExactly(
        "Support message "+receipt.reference()+" was saved but its email was not sent (MailSendException); the receipt stays unconfirmed",
        "Support message refused with 503: app.support.inbox is missing or not an email address, and the request could not be recorded for Team Reviews",
        "Support message refused with 422: its fields or images could not be read (IllegalArgumentException)");
      assertThat(log.lines()).extracting(line->com.lookahead.domain.LogCapture.keys(line).get("reason")).containsExactly(null,"EMAIL_NOT_SENT","REQUEST_NOT_RECORDED","SUPPORT_UNAVAILABLE","FEEDBACK_INVALID");
      log.containsNone("reply@example.test","sender@example.test","support@example.test","smtp.internal","A private message");
    }
  }
  @Test void rejectsHeaderInjectionAndNonImagesBeforeSending() {
    var service=service(new TestReceiptStore());
    assertThatThrownBy(()->service.submit(learner,"request-1234567890",new SupportFeedbackService.Feedback("feedback","subject\r\nBcc: other@example.test","message","reply@example.test",List.of()))).hasMessageContaining("subject");
    assertThatThrownBy(()->service.submit(learner,"request-1234567890",new SupportFeedbackService.Feedback("feedback","subject","message","reply@example.test",List.of(new SupportFeedbackService.Attachment("image.png",Base64.getEncoder().encodeToString("not an image".getBytes())))))).hasMessageContaining("PNG");
    assertThatThrownBy(()->service.submit(null,"request-1234567890",feedback("feedback","subject"))).hasMessageContaining("Sign in");
    assertThatThrownBy(()->service.submit(new AccountPrincipal(UUID.randomUUID(),"off@example.test","Off",false),"request-1234567890",feedback("feedback","subject"))).hasMessageContaining("Sign in");
    verifyNoInteractions(provider);
    assertThat(recorded()).isEmpty();
  }
  @Test void decodesAndDeliversImagesWithGeneratedSafeNames() throws Exception {
    var pixels=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
    pixels.setRGB(0,0,0x336699);
    var bytes=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels,"png",bytes);
    var service=service(new TestReceiptStore());
    var feedback=new SupportFeedbackService.Feedback("problem","Screenshot","Reproduction steps","reply@example.test",List.of(
      new SupportFeedbackService.Attachment("../../untrusted.png",Base64.getEncoder().encodeToString(bytes.toByteArray()))));
    assertThat(service.submit(learner,"request-1234567890",feedback).status()).isEqualTo("accepted");
    mime.saveChanges();
    var root=(jakarta.mail.Multipart)mime.getContent();
    assertThat(root.getCount()).isEqualTo(2);
    var attachment=root.getBodyPart(1);
    assertThat(attachment.getFileName()).isEqualTo("screenshot-1.png");
    assertThat(attachment.getContentType()).startsWith("image/png");
    var decoded=javax.imageio.ImageIO.read(attachment.getInputStream());
    assertThat(decoded.getWidth()).isEqualTo(2);
    assertThat(decoded.getRGB(0,0)&0xffffff).isEqualTo(0x336699);
    verify(sender).send(mime);
    // DLV-971: the same screenshot is stored on the Team Reviews item through the review image pipeline.
    var item=reviews.item(owner,recorded().getFirst().id());
    assertThat(item.attachments()).hasSize(1);
    assertThat(item.attachments().getFirst().name()).isEqualTo("screenshot-1.png");
    assertThat(item.attachments().getFirst().contentType()).isEqualTo("image/png");
    assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(reviews.attachment(owner,item.id(),item.attachments().getFirst().id()).bytes())).getRGB(0,0)&0xffffff).isEqualTo(0x336699);
  }

}
