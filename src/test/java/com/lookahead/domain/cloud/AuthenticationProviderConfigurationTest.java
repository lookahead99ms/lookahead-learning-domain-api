package com.lookahead.domain.cloud;

import com.lookahead.domain.security.DomainSecurityConfiguration;
import com.lookahead.domain.security.IdentitySettings;
import com.lookahead.domain.security.IdentityVerificationClient;
import com.lookahead.learning.content.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/** Actual provider/configuration beans; no provider request, database connection or application server. */
class AuthenticationProviderConfigurationTest {
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
                    assertThat(application).hasNotFailed().hasSingleBean(IdentitySettings.class)
                            .hasSingleBean(IdentityVerificationClient.class).hasSingleBean(JwtDecoder.class)
                            .hasSingleBean(SecurityFilterChain.class).doesNotHaveBean(CognitoSettings.class)
                            .doesNotHaveBean(CognitoIdentityProviderClient.class).doesNotHaveBean(CloudSignInRegistry.class);
                    assertThat(application).hasBean("domainDecoder").hasBean("localAuthenticationConverter")
                            .doesNotHaveBean("cloudDecoder");
                    assertThat(mockingDetails(application.getBean(JdbcTemplate.class)).getInvocations())
                            .allSatisfy(invocation -> assertThat(invocation.getMethod().getName()).isEqualTo("afterPropertiesSet"));
                });
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudSelectsCognitoWithoutIdentityConfigurationOrLocalFallback(String mode) {
        context.withPropertyValues(cloudProperties(mode)).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(CognitoSettings.class)
                    .hasSingleBean(CognitoIdentityProviderClient.class).hasSingleBean(CloudSignInRegistry.class)
                    .hasSingleBean(JwtDecoder.class).doesNotHaveBean(IdentitySettings.class)
                    .doesNotHaveBean(IdentityVerificationClient.class);
            assertThat(application).hasBean("cloudDecoder").hasBean("cloudAuthenticationConverter")
                    .doesNotHaveBean("domainDecoder").doesNotHaveBean("localAuthenticationConverter");
            assertThat(application.getBeansOfType(SecurityFilterChain.class)).hasSize(2);
            var client = application.getBean(CognitoIdentityProviderClient.class).serviceClientConfiguration();
            assertThat(client.region().id()).isEqualTo("us-east-2");
            assertThat(client.credentialsProvider()).isInstanceOf(AnonymousCredentialsProvider.class);
            assertThat(mockingDetails(application.getBean(JdbcTemplate.class)).getInvocations())
                            .allSatisfy(invocation -> assertThat(invocation.getMethod().getName()).isEqualTo("afterPropertiesSet"));
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

    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({DomainSecurityConfiguration.class, CloudSecurityConfiguration.class})
    static class Application {
        @Bean AccountRepository accounts() { return mock(AccountRepository.class); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean PlatformTransactionManager transactions() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper mapper() { return new JsonMapper(); }
    }
}
