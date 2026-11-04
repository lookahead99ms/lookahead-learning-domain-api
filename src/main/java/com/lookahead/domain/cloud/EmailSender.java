package com.lookahead.domain.cloud;

/**
 * DLV-969 S36: the Domain's own plain-text email (the merge link). {@link SesEmailSender} when {@code app.email.enabled}
 * is true, otherwise {@link LoggingEmailSender}; tests use an in-memory fake. A failure is a RuntimeException.
 */
public interface EmailSender {
    void send(String to, String subject, String textBody);
}
