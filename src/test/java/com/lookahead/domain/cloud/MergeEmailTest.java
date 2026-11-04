package com.lookahead.domain.cloud;

import com.lookahead.domain.access.AccountRemovalService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-969 S36: the merge email's senders and wiring; no AWS call is ever made. */
class MergeEmailTest {
    @Test void sesSendsOnePlainTextEmailFromTheConfiguredAddress(){
        var client=mock(SesV2Client.class,withSettings().mockMaker(MockMakers.SUBCLASS));
        try(var log=com.lookahead.domain.LogCapture.start()){
            new SesEmailSender(client,"no-reply@dev.devlookahead.com").send("paula@gmail.invalid","Subject line","Body with a link");
            // DLV-969 log: one lookahead.upstream line per SES call; never the address, subject or body.
            var call=log.only("upstream.call");
            assertThat(call.getLoggerName()).isEqualTo("lookahead.upstream");assertThat(call.getFormattedMessage()).matches("SES SendEmail succeeded in \\d+ ms");
            assertThat(com.lookahead.domain.LogCapture.keys(call)).containsEntry("upstream.service","ses").containsEntry("upstream.operation","SendEmail").containsEntry("event.outcome","success");
            log.containsNone("paula@gmail.invalid","Subject line","Body with a link");
        }
        var sent=ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(sent.capture());
        var request=sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("no-reply@dev.devlookahead.com");
        assertThat(request.destination().toAddresses()).containsExactly("paula@gmail.invalid");
        assertThat(request.destination().ccAddresses()).isEmpty();assertThat(request.destination().bccAddresses()).isEmpty();
        var simple=request.content().simple();
        assertThat(simple.subject().data()).isEqualTo("Subject line");assertThat(simple.subject().charset()).isEqualTo("UTF-8");
        assertThat(simple.body().text().data()).isEqualTo("Body with a link");assertThat(simple.body().html()).isNull();
        assertThat(request.content().template()).isNull();assertThat(request.configurationSetName()).isNull();
    }
    @Test void theTaskRoleClientIsBuiltForTheRegionWithoutRetry(){
        try(var built=SesEmailSender.taskRoleClient("us-east-2")){
            assertThat(built.serviceClientConfiguration().region().id()).isEqualTo("us-east-2");
            assertThat(built.serviceClientConfiguration().endpointOverride()).contains(java.net.URI.create("https://email.us-east-2.amazonaws.com"));
            assertThat(built.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(java.time.Duration.ofSeconds(5));
        }
    }
    @Test void sesFailureIsOneUpstreamLineWithTheAwsCodeAndIsRethrown(){
        var client=mock(SesV2Client.class,withSettings().mockMaker(MockMakers.SUBCLASS));
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(software.amazon.awssdk.services.sesv2.model.MessageRejectedException.builder().message("Email address is not verified: paula@gmail.invalid").statusCode(400)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode("MessageRejected").build()).build());
        try(var log=com.lookahead.domain.LogCapture.start()){
            assertThatThrownBy(()->new SesEmailSender(client,"no-reply@dev.devlookahead.com").send("paula@gmail.invalid","Subject line","Body"))
                .isInstanceOf(software.amazon.awssdk.services.sesv2.model.MessageRejectedException.class);
            var call=log.only("upstream.call");
            assertThat(call.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(call.getFormattedMessage()).matches("SES SendEmail failed in \\d+ ms: MessageRejectedException \\(HTTP 400, MessageRejected\\)");
            assertThat(com.lookahead.domain.LogCapture.keys(call)).containsEntry("event.outcome","failure").containsEntry("upstream.status",400).containsEntry("upstream.code","MessageRejected");
            // The AWS message named the address; only the exception type reaches the log.
            log.containsNone("paula@gmail.invalid","not verified");
        }
    }
    @Test void theFallbackLogsOnlyAMaskedAddress(){
        assertThat(LoggingEmailSender.mask("paula@gmail.com")).isEqualTo("p•••a@gmail.com");
        assertThat(LoggingEmailSender.mask("p@gmail.com")).isEqualTo("p•••@gmail.com");
        for(String odd:new String[]{null,"","@gmail.com","paula@","no-at"})assertThat(LoggingEmailSender.mask(odd)).isEqualTo("•••");
        try(var log=com.lookahead.domain.LogCapture.start()){
            assertThatCode(()->new LoggingEmailSender().send("paula@gmail.com","Subject","secret link")).doesNotThrowAnyException();
            // DLV-969 log: the owner sees in CloudWatch that sending is still off and which switch turns it on.
            var skipped=log.only("email.skipped");
            assertThat(skipped.getFormattedMessage()).isEqualTo("Email not sent, app.email.enabled is off (LOOKAHEAD_EMAIL_ENABLED): to p•••a@gmail.com, subject \"Subject\"");
            assertThat(com.lookahead.domain.LogCapture.keys(skipped)).containsEntry("reason","EMAIL_DISABLED");
            log.containsNone("paula@gmail.com","secret link");
        }
    }
    @Test void sesIsWiredOnlyWhenEnabledAndThenNeedsItsSettings(){
        var config=new CloudMergeConfiguration();
        assertThat(config.emailSender(new MockEnvironment())).isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(new MockEnvironment().withProperty("app.email.enabled","false"))).isInstanceOf(LoggingEmailSender.class);
        var enabled=new MockEnvironment().withProperty("app.email.enabled","true").withProperty("app.email.from","no-reply@dev.devlookahead.com").withProperty("app.email.region","us-east-2");
        assertThat(config.emailSender(enabled)).isInstanceOf(SesEmailSender.class);
        assertThatThrownBy(()->config.emailSender(new MockEnvironment().withProperty("app.email.enabled","true").withProperty("app.email.region","us-east-2")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOOKAHEAD_EMAIL_FROM");
        assertThatThrownBy(()->config.emailSender(new MockEnvironment().withProperty("app.email.enabled","true").withProperty("app.email.from","no-reply@dev.devlookahead.com")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_REGION");
        // The display-name form SES accepts as FromEmailAddress; nothing that could add a header or a second address.
        for(String from:new String[]{"Look Ahead <no-reply@dev.devlookahead.com>","no-reply@dev.devlookahead.com","O'Neil Team <a.b-c@x.example>"})
            assertThat(from.matches(CloudMergeConfiguration.FROM)).as(from).isTrue();
        for(String from:new String[]{"","Look Ahead","<no-reply@dev.devlookahead.com>","Look Ahead no-reply@dev.devlookahead.com","a@b.c, d@e.f","Look <a@b.c> <d@e.f>",
                "Look\r\nBcc: x@y.z <a@b.c>","\"Look\" <a@b.c>","no-reply@localhost"})
            assertThat(from.matches(CloudMergeConfiguration.FROM)).as(from).isFalse();
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);var removal=mock(AccountRemovalService.class,withSettings().mockMaker(MockMakers.SUBCLASS));
        assertThat(config.cloudMerges(jdbc,manager,removal,new LoggingEmailSender(),new MockEnvironment().withProperty("app.public-url","https://dev.devlookahead.com"))).isNotNull();
        for(String url:new String[]{"http://dev.devlookahead.com","https://dev.devlookahead.com/path","javascript:alert(1)"})
            assertThatThrownBy(()->config.cloudMerges(jdbc,manager,removal,new LoggingEmailSender(),new MockEnvironment().withProperty("app.public-url",url))).as(url)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
    }
    /** Unset, the link origin defaults only in dev; elsewhere it is required as soon as the email is sent. */
    @Test void thePublicUrlDefaultsOnlyInDevAndIsRequiredWhereEmailIsSent(){
        assertThat(CloudMergeConfiguration.publicUrl(new MockEnvironment().withProperty("app.deployment-environment","dev"))).isEqualTo("https://dev.devlookahead.com");
        assertThat(CloudMergeConfiguration.publicUrl(new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.email.enabled","true"))).isEqualTo("https://dev.devlookahead.com");
        // PROD without email still starts; with email it names the variable.
        assertThat(CloudMergeConfiguration.publicUrl(new MockEnvironment().withProperty("app.deployment-environment","prod"))).isEmpty();
        assertThatThrownBy(()->CloudMergeConfiguration.publicUrl(new MockEnvironment().withProperty("app.deployment-environment","prod").withProperty("app.email.enabled","true")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
        assertThat(CloudMergeConfiguration.publicUrl(new MockEnvironment().withProperty("app.deployment-environment","prod").withProperty("app.email.enabled","true")
            .withProperty("app.public-url","https://www.example.org"))).isEqualTo("https://www.example.org");
        assertThatThrownBy(()->CloudMergeConfiguration.publicUrl(new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.public-url","http://x.example")))
            .hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
    }
    /** application.yml binds exactly LOOKAHEAD_EMAIL_ENABLED, LOOKAHEAD_EMAIL_FROM, AWS_REGION and LOOKAHEAD_PUBLIC_URL. */
    @Test void applicationYamlBindsTheTaskVariables()throws Exception {
        var config=new CloudMergeConfiguration();
        java.util.function.Function<java.util.Map<String,Object>,org.springframework.core.env.StandardEnvironment> env=variables->{
            var environment=new org.springframework.core.env.StandardEnvironment();
            var sources=environment.getPropertySources();
            sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.addFirst(new org.springframework.core.env.MapPropertySource("task",variables));
            try{for(var yml:new org.springframework.boot.env.YamlPropertySourceLoader().load("application",new org.springframework.core.io.ClassPathResource("application.yml")))sources.addLast(yml);}
            catch(java.io.IOException unreadable){throw new IllegalStateException(unreadable);}
            return environment;
        };
        var unset=env.apply(java.util.Map.of());
        assertThat(unset.getProperty("app.email.enabled")).isEqualTo("false");assertThat(unset.getProperty("app.email.from")).isEmpty();
        assertThat(unset.getProperty("app.public-url")).isEmpty();
        assertThat(config.emailSender(unset)).isInstanceOf(LoggingEmailSender.class);
        var dev=env.apply(java.util.Map.of("LOOKAHEAD_EMAIL_ENABLED","false","LOOKAHEAD_EMAIL_FROM","Look Ahead <no-reply@dev.devlookahead.com>",
            "LOOKAHEAD_PUBLIC_URL","https://dev.devlookahead.com","AWS_REGION","us-east-2"));
        assertThat(dev.getProperty("app.email.from")).isEqualTo("Look Ahead <no-reply@dev.devlookahead.com>");
        assertThat(dev.getProperty("app.email.region")).isEqualTo("us-east-2");
        assertThat(config.emailSender(dev)).isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(env.apply(java.util.Map.of("LOOKAHEAD_EMAIL_ENABLED","true","LOOKAHEAD_EMAIL_FROM","Look Ahead <no-reply@dev.devlookahead.com>","AWS_REGION","us-east-2"))))
            .isInstanceOf(SesEmailSender.class);
        assertThatThrownBy(()->config.emailSender(env.apply(java.util.Map.of("LOOKAHEAD_EMAIL_ENABLED","true","AWS_REGION","us-east-2"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOOKAHEAD_EMAIL_FROM");
        var removal=mock(AccountRemovalService.class,withSettings().mockMaker(MockMakers.SUBCLASS));
        assertThatThrownBy(()->config.cloudMerges(mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),removal,new LoggingEmailSender(),
            env.apply(java.util.Map.of("LOOKAHEAD_PUBLIC_URL","http://dev.devlookahead.com")))).hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
    }
    @Test void theBodyCarriesTheLinkTheWarningAndTheWayOut(){
        String body=CloudMergeService.body("https://dev.devlookahead.com/account/merge?token=T");
        assertThat(body).contains("https://dev.devlookahead.com/account/merge?token=T\n").contains(CloudMergeService.WARNING).contains("within 30 minutes")
            .contains("If it wasn't you, ignore this email. Nothing changes.").doesNotContain("<");
        assertThat(CloudMergeService.percent(null,"{}")).isZero();
        assertThat(CloudMergeService.percent("{}",null)).isZero();
        assertThat(CloudMergeService.percent("not json","{}")).isZero();
        assertThat(CloudMergeService.percent("{}","{}")).isZero();
        assertThat(CloudMergeService.percent("{\"config\":{},\"days\":[{\"assignments\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}]}]}","{\"completedSessionIds\":[\"a\"]}")).isEqualTo(33);
        assertThat(CloudMergeService.percent("{\"config\":{},\"days\":[]}","{\"completedSessionIds\":[]}")).isZero();
    }
}
