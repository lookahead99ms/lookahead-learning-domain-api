package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * DLV-969 S36: plain-text email through SES v2 with the task role (ContainerCredentialsProvider). The task role needs
 * ses:SendEmail on the identity of {@code app.email.from}. One attempt, 5 s in all; a failure is the SDK's exception.
 */
public final class SesEmailSender implements EmailSender {
    private final SesV2Client client;
    private final String from;
    public SesEmailSender(SesV2Client client,String from){this.client=client;this.from=from;}

    static SesV2Client taskRoleClient(String region){
        return SesV2Client.builder().region(Region.of(region))
            .credentialsProvider(ContainerCredentialsProvider.builder().build())
            .endpointOverride(URI.create("https://email."+region+".amazonaws.com"))
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3)))
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3)).retryPolicy(RetryPolicy.none()))
            .build();
    }
    /** One {@code lookahead.upstream} line per call (DLV-969): SES SendEmail succeeded or failed, with the AWS code; never the address or body. */
    @Override public void send(String to,String subject,String textBody){
        long started=Events.started();
        try{
            client.sendEmail(SendEmailRequest.builder().fromEmailAddress(from).destination(d->d.toAddresses(to))
                .content(c->c.simple(m->m.subject(s->s.data(subject).charset("UTF-8")).body(b->b.text(t->t.data(textBody).charset("UTF-8")))))
                .build());
            Events.upstreamDone("SES","SendEmail",started);
        }catch(RuntimeException failure){Events.upstreamFailed("SES","SendEmail",started,failure);throw failure;}
    }
}
