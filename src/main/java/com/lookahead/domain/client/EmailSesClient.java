package com.lookahead.domain.client;

import com.lookahead.domain.log.EventLog;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * DLV-969 S36: plain-text email through SES v2 with the task role (ContainerCredentialsProvider).
 * The task role needs ses:SendEmail on the identity of {@code app.email.from}. One attempt, 5 s in
 * all; a failure is the SDK's exception.
 */
public final class EmailSesClient implements EmailClient {
  private final SesV2Client client;
  private final String from;

  public EmailSesClient(SesV2Client client, String from) {
    this.client = client;
    this.from = from;
  }

  /**
   * One {@code lookahead.upstream} line per call (DLV-969): SES SendEmail succeeded or failed, with
   * the AWS code; never the address or body.
   */
  @Override
  public void send(String to, String subject, String textBody) {
    long started = EventLog.started();
    try {
      client.sendEmail(
          SendEmailRequest.builder()
              .fromEmailAddress(from)
              .destination(d -> d.toAddresses(to))
              .content(c ->
                  c.simple(m ->
                      m.subject(s -> s.data(subject).charset("UTF-8"))
                          .body(b -> b.text(t -> t.data(textBody).charset("UTF-8")))))
              .build());
      EventLog.upstreamDone("SES", "SendEmail", started);
    } catch (RuntimeException failure) {
      EventLog.upstreamFailed("SES", "SendEmail", started, failure);
      throw failure;
    }
  }
}
