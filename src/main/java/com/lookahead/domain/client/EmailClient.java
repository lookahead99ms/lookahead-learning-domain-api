package com.lookahead.domain.client;

/**
 * DLV-969 S36: the Domain's own plain-text email (the merge link). {@link EmailSesClient} when
 * {@code app.email.enabled} is true, otherwise {@link EmailLoggingClient}; tests use an in-memory
 * fake. A failure is a RuntimeException.
 */
public interface EmailClient {
  void send(String to, String subject, String textBody);
}
