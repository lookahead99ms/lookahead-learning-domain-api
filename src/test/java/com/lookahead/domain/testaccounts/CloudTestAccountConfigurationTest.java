package com.lookahead.domain.testaccounts;

import com.lookahead.domain.cloud.CloudSchedulingConfiguration;
import com.lookahead.domain.cloud.CloudSecurityConfiguration;
import com.lookahead.domain.cloud.CognitoSettings;
import com.lookahead.domain.security.DomainSecurityConfiguration;
import com.lookahead.learning.content.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Bean wiring only: no provider request, database connection or application server. */
class CloudTestAccountConfigurationTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withUserConfiguration(Application.class).withPropertyValues("spring.profiles.active=accounts,resource");
    private static final String[] LOCAL = {"app.deployment-environment=local",
            "app.identity.issuer=http://127.0.0.1:4380", "app.identity.upstream=http://identity:8080",
            "app.identity.gateway-client-id=lookahead-web-gateway",
            "app.identity.verifier-secret=synthetic-identity-verifier-secret-for-unit-tests"};
    private static String[] cloud(String mode) {
        return new String[]{"app.deployment-environment=" + mode,
                "app.cognito.issuer=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example",
                "app.cognito.region=us-east-2", "app.cognito.client-id=syntheticclient123456",
                "app.cognito.scope-prefix=lookahead",
                "app.cognito.gateway-secret=synthetic-gateway-proof-secret-for-unit-tests"};
    }
    @Test void localContextsHaveNoCognitoOperatorWithOrWithoutTheFlag() {
        context.withPropertyValues(LOCAL).run(application -> assertThat(application).hasNotFailed()
                .hasSingleBean(TestAccountPolicy.class).doesNotHaveBean(CognitoTestIdentityOperator.class)
                .doesNotHaveBean(CloudTestAccountSettings.class).doesNotHaveBean(CognitoIdentityProviderClient.class));
        context.withPropertyValues(LOCAL).withPropertyValues("app.test-accounts.identity=local", "app.test-accounts.pool-id=")
                .run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(CognitoTestIdentityOperator.class));
        context.withPropertyValues(LOCAL).withPropertyValues("app.test-accounts.identity=cognito", "app.test-accounts.pool-id=us-east-2_Example")
                .run(application -> assertThat(application).hasFailed());
    }
    @Test void devWithCognitoIdentityAddsTheOperatorBesideTheSingleAnonymousVerifierClient() {
        context.withPropertyValues(cloud("dev")).withPropertyValues("app.test-accounts.identity=cognito", "app.test-accounts.pool-id=us-east-2_Example")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(CognitoTestIdentityOperator.class)
                            .hasSingleBean(CloudTestAccountSettings.class).hasSingleBean(CognitoSettings.class)
                            .hasSingleBean(CognitoIdentityProviderClient.class);
                    assertThat(application.getBean(CognitoIdentityProviderClient.class).serviceClientConfiguration().credentialsProvider())
                            .isInstanceOf(AnonymousCredentialsProvider.class);
                    assertThat(application.getBean(CloudTestAccountSettings.class).poolId()).isEqualTo("us-east-2_Example");
                    assertThat(application.getBean(TestAccountPolicy.class).cloud()).isTrue();
                    assertThat(application.getBean(TestAccountPolicy.class).local()).isFalse();
                });
        context.withPropertyValues(cloud("dev")).run(application -> assertThat(application).hasNotFailed()
                .doesNotHaveBean(CognitoTestIdentityOperator.class).doesNotHaveBean(CloudTestAccountSettings.class));
        context.withPropertyValues(cloud("dev")).withPropertyValues("app.test-accounts.identity=cognito")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues(cloud("dev")).withPropertyValues("app.test-accounts.identity=cognito", "app.test-accounts.pool-id=us-east-2_Other")
                .run(application -> assertThat(application).hasFailed());
    }
    @Test void prodRefusesTestAccountsEvenWhenTheFlagIsSet() {
        context.withPropertyValues(cloud("prod")).withPropertyValues("app.test-accounts.identity=cognito", "app.test-accounts.pool-id=us-east-2_Example")
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure()).rootCause().hasMessageContaining("never exist");
                });
        context.withPropertyValues(cloud("prod")).run(application -> assertThat(application).hasNotFailed()
                .doesNotHaveBean(CognitoTestIdentityOperator.class));
    }
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({DomainSecurityConfiguration.class, CloudSecurityConfiguration.class, CloudSchedulingConfiguration.class,
            CloudTestAccountConfiguration.class, TestAccountPolicy.class})
    static class Application {
        @Bean AccountRepository accounts() { return mock(AccountRepository.class); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean PlatformTransactionManager transactions() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper mapper() { return new JsonMapper(); }
    }
}
