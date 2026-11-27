package com.lookahead.domain.client;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** The support email: which setting is missing, and the one message that is sent. */
class SupportEmailSmtpClientTest {
    @SuppressWarnings("unchecked") final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final JavaMailSender mail = mock(JavaMailSender.class, withSettings().mockMaker(MockMakers.SUBCLASS));

    @Test void theMissingSettingIsNamedInOrderAndNeverAnAddress() {
        assertThat(new SupportEmailSmtpClient(provider, "sender@example.test", "inbox@example.test").missing()).isEqualTo("spring.mail.host (no mail sender is configured)");
        when(provider.getIfAvailable()).thenReturn(mail);
        assertThat(new SupportEmailSmtpClient(provider, "", "inbox@example.test").missing()).isEqualTo("app.support.from");
        assertThat(new SupportEmailSmtpClient(provider, "sender@example.test", "not an address").missing()).isEqualTo("app.support.inbox");
        assertThat(new SupportEmailSmtpClient(provider, "sender@example.test", "inbox@example.test").missing()).isNull();
    }

    @Test void oneMessageGoesToTheInboxWithTheReferenceAndTheReplyAddress() throws Exception {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        new SupportEmailSmtpClient(provider, "sender@example.test", "inbox@example.test")
                .send("SUP-000001", "problem", "It does not load", "The page stays blank.", "learner@example.test", List.of());
        var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getSubject()).isEqualTo("[problem] It does not load");
        assertThat(sent.getValue().getAllRecipients()[0].toString()).isEqualTo("inbox@example.test");
        assertThat(sent.getValue().getReplyTo()[0].toString()).isEqualTo("learner@example.test");
        assertThat(sent.getValue().getContent().toString()).isEqualTo("Reference: SUP-000001\n\nThe page stays blank.");
    }
}
