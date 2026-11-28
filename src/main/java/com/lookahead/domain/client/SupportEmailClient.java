package com.lookahead.domain.client;

import java.util.List;

/**
 * The support inbox as the support feedback service sees it: can an email go, and send one. {@link
 * SupportEmailSmtpClient} sends it through the mail server.
 */
public interface SupportEmailClient {
  /** One attachment of the email: a screenshot already converted to PNG. */
  record Image(String name, byte[] bytes) {}

  /** The setting that keeps the email from being sent, or null when it can go. */
  String missing();

  /**
   * One email with the reference in its body and the screenshots attached; any failure is thrown as
   * it is.
   */
  void send(
      String reference,
      String type,
      String subject,
      String message,
      String replyTo,
      List<Image> images)
      throws Exception;
}
