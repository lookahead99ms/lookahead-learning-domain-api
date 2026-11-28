package com.lookahead.domain.client;

import com.lookahead.domain.util.SupportFeedbackRules;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends a learner's support request to the support inbox through the mail server (Spring's {@link
 * JavaMailSender}, when one is configured). It also says which setting is missing when the email
 * cannot be sent, by the setting's name only. DLV-1016: the class behind {@link
 * SupportEmailClient}, which is what the service and the validator know.
 */
@Component
@Profile("accounts")
public class SupportEmailSmtpClient implements SupportEmailClient {
  private final ObjectProvider<JavaMailSender> sender;
  private final String from;
  private final String inbox;

  public SupportEmailSmtpClient(
      ObjectProvider<JavaMailSender> sender,
      @Value("${app.support.from:}") String from,
      @Value("${app.support.inbox:}") String inbox) {
    this.sender = sender;
    this.from = from;
    this.inbox = inbox;
  }

  @Override
  public String missing() {
    JavaMailSender mail = sender.getIfAvailable();
    // DLV-969 (owner, 2026-11-04): name the missing setting, never an address.
    String missing =
        mail == null
            ? "spring.mail.host (no mail sender is configured)"
            : !SupportFeedbackRules.email(from)
                ? "app.support.from"
                : !SupportFeedbackRules.email(inbox) ? "app.support.inbox" : null;
    return missing;
  }

  @Override
  public void send(
      String reference,
      String type,
      String subject,
      String message,
      String replyTo,
      List<Image> images)
      throws Exception {
    JavaMailSender mail = sender.getIfAvailable();
    var mime = mail.createMimeMessage();
    var helper = new MimeMessageHelper(mime, !images.isEmpty(), "UTF-8");
    helper.setFrom(from);
    helper.setTo(inbox);
    helper.setReplyTo(replyTo);
    helper.setSubject("[" + type + "] " + subject);
    helper.setText("Reference: " + reference + "\n\n" + message, false);
    for (Image image : images)
      helper.addAttachment(image.name(), new ByteArrayResource(image.bytes()), "image/png");
    mail.send(mime);
  }
}
