package com.lookahead.domain.security.config;

import com.lookahead.domain.config.SignInConfiguration;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.local.identity.IdentityConfiguration;
import com.lookahead.domain.local.identity.IdentityConnection;
import com.lookahead.domain.config.SweepSchedulingConfiguration;
import com.lookahead.domain.job.DevAccessCleanupJob;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.local.identity.IdentityVerificationClient;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import com.lookahead.domain.validator.SignInTokenTrustValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/** Actual provider/configuration beans; no provider request, store call or application server. */
class SignInSecurityConfigurationProviderTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withUserConfiguration(Application.class)
            .withPropertyValues("spring.profiles.active=accounts,resource");

    private static String[] cloudProperties(String mode) {
        return new String[]{"app.deployment-environment=" + mode,
                "app.cognito.issuer=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example",
                "app.cognito.region=us-east-2", "app.cognito.client-id=syntheticclient123456",
                "app.cognito.scope-prefix=lookahead",
                "app.cognito.gateway-secret=synthetic-gateway-proof-secret-for-unit-tests"};
    }

    @Test void localSelectsIdentityWithoutAnyCognitoConfigurationOrSdkBean() {
        context.withPropertyValues("app.deployment-environment=local",
                "app.identity.issuer=http://127.0.0.1:4380", "app.identity.upstream=http://identity:8080",
                "app.identity.gateway-client-id=lookahead-web-gateway",
                "app.identity.verifier-secret=synthetic-identity-verifier-secret-for-unit-tests")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(IdentityConnection.class)
                            .hasSingleBean(IdentityVerificationClient.class).hasSingleBean(JwtDecoder.class)
                            .hasSingleBean(SecurityFilterChain.class).doesNotHaveBean(SignInTokenTrust.class)
                            .doesNotHaveBean(CognitoIdentityProviderClient.class).doesNotHaveBean(SignInService.class)
                            .doesNotHaveBean(DevAccessCleanupJob.class).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(application).hasBean("identityDecoder").hasBean("localAuthenticationConverter")
                            .doesNotHaveBean("signInDecoder");
                    assertThat(mockingDetails(application.getBean(DynamoDbClient.class)).getInvocations()).isEmpty();
                });
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudSelectsCognitoWithoutIdentityConfigurationOrLocalFallback(String mode) {
        context.withPropertyValues(cloudProperties(mode)).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(SignInTokenTrust.class)
                    // DLV-1016: one checked pass (signInCheckedValues), unpacked into the token trust and the Gateway BFF secret.
                    .hasSingleBean(SignInTokenTrust.Checked.class).hasSingleBean(SignInBffSecret.class)
                    .hasBean("cognitoUserClient").hasBean("cognitoTaskRoleClient").hasSingleBean(SignInService.class)
                    .hasSingleBean(JwtDecoder.class).doesNotHaveBean(IdentityConnection.class)
                    .doesNotHaveBean(IdentityVerificationClient.class)
                    .hasSingleBean(DevAccessCleanupJob.class).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(application).hasBean("signInDecoder").hasBean("signInTokenConverter")
                    .doesNotHaveBean("identityDecoder").doesNotHaveBean("localAuthenticationConverter");
            assertThat(application.getBeansOfType(SecurityFilterChain.class)).hasSize(2);
            // The two Cognito connections: one without AWS credentials (the person's own token), one with the task role (owner actions).
            assertThat(application.getBeansOfType(CognitoIdentityProviderClient.class)).containsOnlyKeys("cognitoUserClient", "cognitoTaskRoleClient");
            assertThat(application.getBean("cognitoTaskRoleClient", CognitoIdentityProviderClient.class).serviceClientConfiguration().credentialsProvider())
                    .isInstanceOf(ContainerCredentialsProvider.class);
            var client = application.getBean("cognitoUserClient", CognitoIdentityProviderClient.class).serviceClientConfiguration();
            assertThat(client.region().id()).isEqualTo("us-east-2");
            assertThat(client.credentialsProvider()).isInstanceOf(AnonymousCredentialsProvider.class);
            assertThat(mockingDetails(application.getBean(DynamoDbClient.class)).getInvocations()).isEmpty();
        });
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void incompleteCloudConfigurationCannotActivateTheLocalProvider(String mode) {
        context.withPropertyValues("app.deployment-environment=" + mode,
                "app.identity.issuer=http://127.0.0.1:4380", "app.identity.upstream=http://identity:8080",
                "app.identity.gateway-client-id=lookahead-web-gateway",
                "app.identity.verifier-secret=synthetic-identity-verifier-secret-for-unit-tests")
                .run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings = {"", "test", "production"})
    void unsupportedModesCannotCreateASecurityChain(String mode) {
        context.withPropertyValues(cloudProperties(mode))
                .run(application -> assertThat(application).hasFailed());
    }

    /**
     * DLV-1016: the sign-in settings are checked once, in one pass, however many beans are made from the result. With the
     * client ID and the Gateway secret both wrong, the start is refused once, for the client ID (the earlier check).
     */
    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void severalWrongSignInSettingsAreRefusedOnceForTheFirstOfThem(String mode) {
        try (var log = LogCapture.start("lookahead.startup")) {
            context.withPropertyValues(cloudProperties(mode)).withPropertyValues("app.cognito.client-id=short", "app.cognito.gateway-secret=short")
                    .run(application -> assertThat(application).hasFailed().getFailure().rootCause()
                            .isExactlyInstanceOf(IllegalStateException.class).hasMessage(SignInTokenTrustValidator.INVALID));
            var line = log.only("startup.check");
            assertThat(line.getFormattedMessage()).isEqualTo("Startup refused: app.cognito.client-id (APP_OAUTH_CLIENT_ID) is not 10 to 128 letters and digits;"
                    + " the app stops before it takes traffic");
        }
    }

    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({ApiSecurityConfiguration.class, IdentityConfiguration.class, SignInSecurityConfiguration.class, SignInConfiguration.class, SweepSchedulingConfiguration.class})
    static class Application {
        @Bean AccountGrantService accounts() { return mock(AccountGrantService.class); }
        /** DLV-1016: in DEV the API chain is the variant with test sessions; the shipped DEV context always has this bean (TestSessionConfiguration). */
        @Bean TestSessionService testSessions() { return mock(TestSessionService.class); }
        @Bean DynamoDbClient dynamoDbClient() { return mock(DynamoDbClient.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS)); }
        @Bean DynamoTableNames dynamoTableNames() { return DynamoTableNames.local(); }
        @Bean PlatformTransactionManager transactions() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper mapper() { return new JsonMapper(); }
    }
}
