package com.lookahead.domain.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.client.EmailClient;
import com.lookahead.domain.client.EmailLoggingClient;
import com.lookahead.domain.client.EmailSesClient;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.signin.AccountMergeService;
import com.lookahead.domain.util.AccountMergeRules;
import com.lookahead.domain.validator.AccountMergeEmailConfigValidator;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/** DLV-969 S36: the merge email's senders and wiring; no AWS call is ever made. */
class AccountMergeConfigurationEmailTest {
  /** The SES connection bean as the configuration declares it: built only when asked for. */
  static ObjectProvider<SesV2Client> ses(AccountMergeConfiguration config, Environment env) {
    var beans = new DefaultListableBeanFactory();
    beans.registerBeanDefinition(
        "emailSesConnection",
        BeanDefinitionBuilder.genericBeanDefinition(
                SesV2Client.class,
                () -> config.emailSesConnection(config.accountMergeEmailConfigValues(env)))
            .setLazyInit(true)
            .getBeanDefinition());
    return beans.getBeanProvider(SesV2Client.class);
  }

  /**
   * DLV-1016: the conditions on the two bean methods choose the client; this repeats their rule for
   * a hand-built configuration.
   */
  static EmailClient emailClient(
      AccountMergeConfiguration config, Environment env, ObjectProvider<SesV2Client> ses) {
    var values = config.accountMergeEmailConfigValues(env);
    return values.enabled()
        ? config.emailSesClient(values, ses.getObject())
        : config.emailLoggingClient();
  }

  @SuppressWarnings("unchecked")
  static <T> ObjectProvider<T> cast(ObjectProvider<?> provider) {
    return (ObjectProvider<T>) provider;
  }

  @Test
  void sesSendsOnePlainTextEmailFromTheConfiguredAddress() {
    var client = mock(SesV2Client.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    try (var log = LogCapture.start()) {
      new EmailSesClient(client, "no-reply@dev.devlookahead.com")
          .send("paula@gmail.invalid", "Subject line", "Body with a link");
      // DLV-969 log: one lookahead.upstream line per SES call; never the address, subject or body.
      var call = log.only("upstream.call");
      assertThat(call.getLoggerName()).isEqualTo("lookahead.upstream");
      assertThat(call.getFormattedMessage()).matches("SES SendEmail succeeded in \\d+ ms");
      assertThat(LogCapture.keys(call))
          .containsEntry("upstream.service", "ses")
          .containsEntry("upstream.operation", "SendEmail")
          .containsEntry("event.outcome", "success");
      log.containsNone("paula@gmail.invalid", "Subject line", "Body with a link");
    }
    var sent = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(client).sendEmail(sent.capture());
    var request = sent.getValue();
    assertThat(request.fromEmailAddress()).isEqualTo("no-reply@dev.devlookahead.com");
    assertThat(request.destination().toAddresses()).containsExactly("paula@gmail.invalid");
    assertThat(request.destination().ccAddresses()).isEmpty();
    assertThat(request.destination().bccAddresses()).isEmpty();
    var simple = request.content().simple();
    assertThat(simple.subject().data()).isEqualTo("Subject line");
    assertThat(simple.subject().charset()).isEqualTo("UTF-8");
    assertThat(simple.body().text().data()).isEqualTo("Body with a link");
    assertThat(simple.body().html()).isNull();
    assertThat(request.content().template()).isNull();
    assertThat(request.configurationSetName()).isNull();
  }

  @Test
  void theTaskRoleClientIsBuiltForTheRegionWithoutRetry() {
    try (var built =
        new AccountMergeConfiguration()
            .emailSesConnection(
                AccountMergeEmailConfigValidator.validated(
                    new MockEnvironment()
                        .withProperty("app.email.enabled", "true")
                        .withProperty("app.email.from", "no-reply@dev.devlookahead.com")
                        .withProperty("app.email.region", "us-east-2")))) {
      assertThat(built.serviceClientConfiguration().region().id()).isEqualTo("us-east-2");
      assertThat(built.serviceClientConfiguration().endpointOverride())
          .contains(URI.create("https://email.us-east-2.amazonaws.com"));
      assertThat(built.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
          .contains(Duration.ofSeconds(5));
    }
  }

  @Test
  void sesFailureIsOneUpstreamLineWithTheAwsCodeAndIsRethrown() {
    var client = mock(SesV2Client.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    when(client.sendEmail(any(SendEmailRequest.class)))
        .thenThrow(
            MessageRejectedException.builder()
                .message("Email address is not verified: paula@gmail.invalid")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build())
                .build());
    try (var log = LogCapture.start()) {
      assertThatThrownBy(() ->
              new EmailSesClient(client, "no-reply@dev.devlookahead.com")
                  .send("paula@gmail.invalid", "Subject line", "Body"))
          .isInstanceOf(MessageRejectedException.class);
      var call = log.only("upstream.call");
      assertThat(call.getLevel()).isEqualTo(Level.WARN);
      assertThat(call.getFormattedMessage())
          .matches(
              "SES SendEmail failed in \\d+ ms: MessageRejectedException \\(HTTP 400,"
                  + " MessageRejected\\)");
      assertThat(LogCapture.keys(call))
          .containsEntry("event.outcome", "failure")
          .containsEntry("upstream.status", 400)
          .containsEntry("upstream.code", "MessageRejected");
      // The AWS message named the address; only the exception type reaches the log.
      log.containsNone("paula@gmail.invalid", "not verified");
    }
  }

  @Test
  void theFallbackLogsOnlyAMaskedAddress() {
    assertThat(EmailLoggingClient.mask("paula@gmail.com")).isEqualTo("p•••a@gmail.com");
    assertThat(EmailLoggingClient.mask("p@gmail.com")).isEqualTo("p•••@gmail.com");
    for (String odd : new String[] {null, "", "@gmail.com", "paula@", "no-at"})
      assertThat(EmailLoggingClient.mask(odd)).isEqualTo("•••");
    try (var log = LogCapture.start()) {
      assertThatCode(
              () -> new EmailLoggingClient().send("paula@gmail.com", "Subject", "secret link"))
          .doesNotThrowAnyException();
      // DLV-969 log: the owner sees in CloudWatch that sending is still off and which switch turns
      // it on.
      var skipped = log.only("email.skipped");
      assertThat(skipped.getFormattedMessage())
          .isEqualTo(
              "Email not sent, app.email.enabled is off (LOOKAHEAD_EMAIL_ENABLED): to"
                  + " p•••a@gmail.com, subject \"Subject\"");
      assertThat(LogCapture.keys(skipped)).containsEntry("reason", "EMAIL_DISABLED");
      log.containsNone("paula@gmail.com", "secret link");
    }
  }

  @Test
  void sesIsWiredOnlyWhenEnabledAndThenNeedsItsSettings() {
    var config = new AccountMergeConfiguration();
    assertThat(emailClient(config, new MockEnvironment(), ses(config, new MockEnvironment())))
        .isInstanceOf(EmailLoggingClient.class);
    assertThat(
            emailClient(
                config,
                new MockEnvironment().withProperty("app.email.enabled", "false"),
                ses(config, new MockEnvironment().withProperty("app.email.enabled", "false"))))
        .isInstanceOf(EmailLoggingClient.class);
    var enabled =
        new MockEnvironment()
            .withProperty("app.email.enabled", "true")
            .withProperty("app.email.from", "no-reply@dev.devlookahead.com")
            .withProperty("app.email.region", "us-east-2");
    assertThat(emailClient(config, enabled, ses(config, enabled)))
        .isInstanceOf(EmailSesClient.class);
    assertThatThrownBy(() ->
            emailClient(
                config,
                new MockEnvironment()
                    .withProperty("app.email.enabled", "true")
                    .withProperty("app.email.region", "us-east-2"),
                ses(
                    config,
                    new MockEnvironment()
                        .withProperty("app.email.enabled", "true")
                        .withProperty("app.email.region", "us-east-2"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOOKAHEAD_EMAIL_FROM");
    assertThatThrownBy(() ->
            emailClient(
                config,
                new MockEnvironment()
                    .withProperty("app.email.enabled", "true")
                    .withProperty("app.email.from", "no-reply@dev.devlookahead.com"),
                ses(
                    config,
                    new MockEnvironment()
                        .withProperty("app.email.enabled", "true")
                        .withProperty("app.email.from", "no-reply@dev.devlookahead.com"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AWS_REGION");
    // The display-name form SES accepts as FromEmailAddress; nothing that could add a header or a
    // second address.
    for (String from :
        new String[] {
          "Look Ahead <no-reply@dev.devlookahead.com>",
          "no-reply@dev.devlookahead.com",
          "O'Neil Team <a.b-c@x.example>"
        }) assertThat(from.matches(AccountMergeEmailConfigValidator.FROM)).as(from).isTrue();
    for (String from :
        new String[] {
          "",
          "Look Ahead",
          "<no-reply@dev.devlookahead.com>",
          "Look Ahead no-reply@dev.devlookahead.com",
          "a@b.c, d@e.f",
          "Look <a@b.c> <d@e.f>",
          "Look\r\nBcc: x@y.z <a@b.c>",
          "\"Look\" <a@b.c>",
          "no-reply@localhost"
        }) assertThat(from.matches(AccountMergeEmailConfigValidator.FROM)).as(from).isFalse();
    var client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var tables = DynamoTableNames.local();
    var removal = mock(AccountRemoval.class, withSettings().mockMaker(MockMakers.PROXY));
    @SuppressWarnings("unchecked")
    ObjectProvider<Object> none =
        mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    assertThat(
            config.accountMergeService(
                config.accountMergeStore(client, tables, cast(none), cast(none), cast(none)),
                removal,
                new EmailLoggingClient(),
                config.accountMergeLinkConfigValues(
                    new MockEnvironment()
                        .withProperty("app.public-url", "https://dev.devlookahead.com"))))
        .isNotNull();
    for (String url :
        new String[] {
          "http://dev.devlookahead.com", "https://dev.devlookahead.com/path", "javascript:alert(1)"
        })
      assertThatThrownBy(() ->
              config.accountMergeService(
                  config.accountMergeStore(client, tables, cast(none), cast(none), cast(none)),
                  removal,
                  new EmailLoggingClient(),
                  config.accountMergeLinkConfigValues(
                      new MockEnvironment().withProperty("app.public-url", url))))
          .as(url)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
  }

  /**
   * Unset, the link origin defaults only in dev; elsewhere it is required as soon as the email is
   * sent.
   */
  @Test
  void thePublicUrlDefaultsOnlyInDevAndIsRequiredWhereEmailIsSent() {
    assertThat(
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment().withProperty("app.deployment-environment", "dev")))
        .isEqualTo("https://dev.devlookahead.com");
    assertThat(
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment()
                    .withProperty("app.deployment-environment", "dev")
                    .withProperty("app.email.enabled", "true")))
        .isEqualTo("https://dev.devlookahead.com");
    // PROD without email still starts; with email it names the variable.
    assertThat(
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment().withProperty("app.deployment-environment", "prod")))
        .isEmpty();
    assertThatThrownBy(() ->
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment()
                    .withProperty("app.deployment-environment", "prod")
                    .withProperty("app.email.enabled", "true")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
    assertThat(
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment()
                    .withProperty("app.deployment-environment", "prod")
                    .withProperty("app.email.enabled", "true")
                    .withProperty("app.public-url", "https://www.example.org")))
        .isEqualTo("https://www.example.org");
    assertThatThrownBy(() ->
            AccountMergeEmailConfigValidator.publicUrl(
                new MockEnvironment()
                    .withProperty("app.deployment-environment", "dev")
                    .withProperty("app.public-url", "http://x.example")))
        .hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
  }

  /**
   * application.yml binds exactly LOOKAHEAD_EMAIL_ENABLED, LOOKAHEAD_EMAIL_FROM, AWS_REGION and
   * LOOKAHEAD_PUBLIC_URL.
   */
  @Test
  void applicationYamlBindsTheTaskVariables() throws Exception {
    var config = new AccountMergeConfiguration();
    Function<Map<String, Object>, StandardEnvironment> env =
        variables -> {
          var environment = new StandardEnvironment();
          var sources = environment.getPropertySources();
          sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
          sources.addFirst(new MapPropertySource("task", variables));
          try {
            for (var yml :
                new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml")))
              sources.addLast(yml);
          } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
          }
          return environment;
        };
    var unset = env.apply(Map.of());
    assertThat(unset.getProperty("app.email.enabled")).isEqualTo("false");
    assertThat(unset.getProperty("app.email.from")).isEmpty();
    assertThat(unset.getProperty("app.public-url")).isEmpty();
    assertThat(emailClient(config, unset, ses(config, unset)))
        .isInstanceOf(EmailLoggingClient.class);
    var dev =
        env.apply(
            Map.of(
                "LOOKAHEAD_EMAIL_ENABLED",
                "false",
                "LOOKAHEAD_EMAIL_FROM",
                "Look Ahead <no-reply@dev.devlookahead.com>",
                "LOOKAHEAD_PUBLIC_URL",
                "https://dev.devlookahead.com",
                "AWS_REGION",
                "us-east-2"));
    assertThat(dev.getProperty("app.email.from"))
        .isEqualTo("Look Ahead <no-reply@dev.devlookahead.com>");
    assertThat(dev.getProperty("app.email.region")).isEqualTo("us-east-2");
    assertThat(emailClient(config, dev, ses(config, dev))).isInstanceOf(EmailLoggingClient.class);
    assertThat(
            emailClient(
                config,
                env.apply(
                    Map.of(
                        "LOOKAHEAD_EMAIL_ENABLED",
                        "true",
                        "LOOKAHEAD_EMAIL_FROM",
                        "Look Ahead <no-reply@dev.devlookahead.com>",
                        "AWS_REGION",
                        "us-east-2")),
                ses(
                    config,
                    env.apply(
                        Map.of(
                            "LOOKAHEAD_EMAIL_ENABLED",
                            "true",
                            "LOOKAHEAD_EMAIL_FROM",
                            "Look Ahead <no-reply@dev.devlookahead.com>",
                            "AWS_REGION",
                            "us-east-2")))))
        .isInstanceOf(EmailSesClient.class);
    assertThatThrownBy(() ->
            emailClient(
                config,
                env.apply(Map.of("LOOKAHEAD_EMAIL_ENABLED", "true", "AWS_REGION", "us-east-2")),
                ses(
                    config,
                    env.apply(
                        Map.of("LOOKAHEAD_EMAIL_ENABLED", "true", "AWS_REGION", "us-east-2")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOOKAHEAD_EMAIL_FROM");
    var removal = mock(AccountRemoval.class, withSettings().mockMaker(MockMakers.PROXY));
    @SuppressWarnings("unchecked")
    ObjectProvider<Object> none =
        mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    assertThatThrownBy(() ->
            config.accountMergeService(
                config.accountMergeStore(
                    mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.SUBCLASS)),
                    DynamoTableNames.local(),
                    cast(none),
                    cast(none),
                    cast(none)),
                removal,
                new EmailLoggingClient(),
                config.accountMergeLinkConfigValues(
                    env.apply(Map.of("LOOKAHEAD_PUBLIC_URL", "http://dev.devlookahead.com")))))
        .hasMessageContaining("LOOKAHEAD_PUBLIC_URL");
  }

  @Test
  void theBodyCarriesTheLinkTheWarningAndTheWayOut() {
    String body = AccountMergeService.body("https://dev.devlookahead.com/account/merge?token=T");
    assertThat(body)
        .contains("https://dev.devlookahead.com/account/merge?token=T\n")
        .contains(AccountMergeService.WARNING)
        .contains("within 30 minutes")
        .contains("If it wasn't you, ignore this email. Nothing changes.")
        .doesNotContain("<");
    assertThat(AccountMergeRules.percent(null, "{}")).isZero();
    assertThat(AccountMergeRules.percent("{}", null)).isZero();
    assertThat(AccountMergeRules.percent("not json", "{}")).isZero();
    assertThat(AccountMergeRules.percent("{}", "{}")).isZero();
    assertThat(
            AccountMergeRules.percent(
                "{\"config\":{},\"days\":[{\"assignments\":[{\"id\":\"a\"},"
                    + "{\"id\":\"b\"},{\"id\":\"c\"}]}]}",
                "{\"completedSessionIds\":[\"a\"]}"))
        .isEqualTo(33);
    assertThat(
            AccountMergeRules.percent(
                "{\"config\":{},\"days\":[]}", "{\"completedSessionIds\":[]}"))
        .isZero();
  }
}
