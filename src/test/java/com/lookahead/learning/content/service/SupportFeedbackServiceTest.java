package com.lookahead.learning.content.service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class SupportFeedbackServiceTest {
  private static class TestReceiptStore implements SupportReceiptStore {
    private final Map<String,Reservation> stored=new HashMap<>(); private final Map<String,String> digests=new HashMap<>();
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
  }

  @SuppressWarnings("unchecked")
  @Test void acceptsOnceAndReplaysWithoutSendingTwice() throws Exception {
    var sender=mock(JavaMailSender.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)); ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    when(provider.getIfAvailable()).thenReturn(sender);
    var mime=new MimeMessage(Session.getInstance(new Properties())); when(sender.createMimeMessage()).thenReturn(mime);
    var service=new SupportFeedbackService(provider,"sender@example.test","support@example.test",new TestReceiptStore());
    var feedback=new SupportFeedbackService.Feedback("feedback","A clear subject","A useful message","reply@example.test",List.of());
    var first=service.submit("owner","request-1234567890",feedback);
    assertThat(first.status()).isEqualTo("accepted");
    assertThat(service.submit("owner","request-1234567890",feedback)).isEqualTo(first);
    verify(sender,times(1)).send(mime);
    assertThat(mime.getReplyTo()[0].toString()).isEqualTo("reply@example.test");
    assertThat(mime.getContent().toString()).contains("A useful message");
    assertThatThrownBy(()->service.submit("owner","request-1234567890",new SupportFeedbackService.Feedback("feedback","Changed","A useful message","reply@example.test",List.of()))).hasMessageContaining("same message");
  }
  @SuppressWarnings("unchecked")
  @Test void unconfirmedSendCannotBeDuplicatedByRetry() {
    var sender=mock(JavaMailSender.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)); ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    when(provider.getIfAvailable()).thenReturn(sender);
    when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    doThrow(new org.springframework.mail.MailSendException("synthetic failure")).when(sender).send(any(MimeMessage.class));
    var service=new SupportFeedbackService(provider,"sender@example.test","support@example.test",new TestReceiptStore());
    var feedback=new SupportFeedbackService.Feedback("problem","A subject","A message","reply@example.test",List.of());
    var receipt=service.submit("owner","request-1234567890",feedback);
    assertThat(receipt.status()).isEqualTo("unconfirmed");
    assertThat(service.submit("owner","request-1234567890",feedback)).isEqualTo(receipt);
    verify(sender,times(1)).send(any(MimeMessage.class));
  }
  /** DLV-969 (owner, 2026-11-04): an email that did not go, a missing setting and an unreadable upload are explained. */
  @SuppressWarnings("unchecked")
  @Test void failuresAreLoggedWithoutAddressesOrMessages() {
    var sender=mock(JavaMailSender.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)); ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    when(provider.getIfAvailable()).thenReturn(sender);
    when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    doThrow(new org.springframework.mail.MailSendException("smtp.internal refused reply@example.test")).when(sender).send(any(MimeMessage.class));
    var feedback=new SupportFeedbackService.Feedback("problem","A subject","A private message","reply@example.test",List.of());
    try(var log=com.lookahead.domain.LogCapture.start()){
      var receipt=new SupportFeedbackService(provider,"sender@example.test","support@example.test",new TestReceiptStore()).submit("owner","request-1234567890",feedback);
      assertThatThrownBy(()->new SupportFeedbackService(provider,"sender@example.test","",new TestReceiptStore()).submit("owner","request-1234567890",feedback))
        .hasMessageContaining("not configured");
      var broken=new SupportFeedbackService.Feedback("problem","A subject","A message","reply@example.test",List.of(new SupportFeedbackService.Attachment("x.png","%%% not base64")));
      assertThatThrownBy(()->new SupportFeedbackService(provider,"sender@example.test","support@example.test",new TestReceiptStore()).submit("owner","request-1234567890",broken))
        .hasMessageContaining("PNG or JPEG");
      assertThat(log.messages()).containsExactly(
        "Support message "+receipt.reference()+" was saved but its email was not sent (MailSendException); the receipt stays unconfirmed",
        "Support message refused with 503: app.support.inbox is missing or not an email address",
        "Support message refused with 422: its fields or images could not be read (IllegalArgumentException)");
      assertThat(log.lines()).extracting(line->com.lookahead.domain.LogCapture.keys(line).get("reason")).containsExactly("EMAIL_NOT_SENT","SUPPORT_UNAVAILABLE","FEEDBACK_INVALID");
      log.containsNone("reply@example.test","sender@example.test","support@example.test","smtp.internal","A private message");
    }
  }
  @SuppressWarnings("unchecked")
  @Test void rejectsHeaderInjectionAndNonImagesBeforeSending() {
    ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    var service=new SupportFeedbackService(provider,"sender@example.test","support@example.test",new TestReceiptStore());
    assertThatThrownBy(()->service.submit("owner","request-1234567890",new SupportFeedbackService.Feedback("feedback","subject\r\nBcc: other@example.test","message","reply@example.test",List.of()))).hasMessageContaining("subject");
    assertThatThrownBy(()->service.submit("owner","request-1234567890",new SupportFeedbackService.Feedback("feedback","subject","message","reply@example.test",List.of(new SupportFeedbackService.Attachment("image.png",Base64.getEncoder().encodeToString("not an image".getBytes())))))).hasMessageContaining("PNG");
    verifyNoInteractions(provider);
  }
  @SuppressWarnings("unchecked")
  @Test void decodesAndDeliversImagesWithGeneratedSafeNames() throws Exception {
    var sender=mock(JavaMailSender.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)); ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    when(provider.getIfAvailable()).thenReturn(sender);
    var mime=new MimeMessage(Session.getInstance(new Properties())); when(sender.createMimeMessage()).thenReturn(mime);
    var pixels=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
    pixels.setRGB(0,0,0x336699);
    var bytes=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels,"png",bytes);
    var service=new SupportFeedbackService(provider,"sender@example.test","support@example.test",new TestReceiptStore());
    var feedback=new SupportFeedbackService.Feedback("problem","Screenshot","Reproduction steps","reply@example.test",List.of(
      new SupportFeedbackService.Attachment("../../untrusted.png",Base64.getEncoder().encodeToString(bytes.toByteArray()))));
    assertThat(service.submit("owner","request-1234567890",feedback).status()).isEqualTo("accepted");
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
  }

}
