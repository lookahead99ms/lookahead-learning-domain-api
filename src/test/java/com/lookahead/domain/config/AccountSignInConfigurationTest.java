package com.lookahead.domain.config;

import com.lookahead.domain.client.AccountSignInClient;
import com.lookahead.domain.client.AccountSignInNotConfiguredClient;
import com.lookahead.domain.client.TestAccountNoSignInClient;
import com.lookahead.domain.client.TestAccountSignInClient;
import com.lookahead.domain.client.TestAccountSignInNotConfiguredClient;
import com.lookahead.domain.local.identity.IdentityConfiguration;
import com.lookahead.domain.local.identity.IdentityOperatorClient;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * DLV-1016: one sign-in client bean per environment, chosen by bean conditions. Before, the Identity operator was a
 * bean everywhere and TestAccountCreationService chose by {@code TestAccountModeService.cloud()}; each case below is the
 * client that choice gave.
 */
class AccountSignInConfigurationTest {
    private static ApplicationContextRunner context(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(AccountSignInConfiguration.class, IdentityConfiguration.class, TestAccountModeService.class)
                .withBean(AccountGrantService.class, () -> mock(AccountGrantService.class, withSettings().mockMaker(MockMakers.SUBCLASS)))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(properties);
    }

    @Test void localHasTheIdentityOperatorForBothClients() {
        context("app.deployment-environment=local", "app.identity.issuer=http://127.0.0.1:4380", "app.identity.upstream=http://identity:8080",
                "app.identity.gateway-client-id=lookahead-web-gateway", "app.identity.verifier-secret=synthetic-verifier-secret-for-unit-tests").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TestAccountModeService.class).cloud()).isFalse();
            assertThat(context.getBeanNamesForType(TestAccountSignInClient.class)).containsExactly("identityOperatorClient");
            assertThat(context.getBeanNamesForType(AccountSignInClient.class)).containsExactly("identityOperatorClient");
            assertThat(context.getBean(TestAccountSignInClient.class)).isInstanceOf(IdentityOperatorClient.class).isSameAs(context.getBean(AccountSignInClient.class));
        });
    }

    @Test void devWithDatabaseOnlyTestAccountsHasNoSignInToCall() {
        context("app.deployment-environment=dev", "app.test-accounts.identity=dev").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(IdentityOperatorClient.class);
            assertThat(context.getBean(TestAccountModeService.class).cloud()).isTrue();
            assertThat(context.getBeanNamesForType(TestAccountSignInClient.class)).containsExactly("testAccountNoSignInClient");
            assertThat(context.getBean(TestAccountSignInClient.class)).isInstanceOf(TestAccountNoSignInClient.class);
            assertThat(context.getBeanNamesForType(AccountSignInClient.class)).containsExactly("accountSignInNotConfiguredClient");
            assertThat(context.getBean(AccountSignInClient.class)).isInstanceOf(AccountSignInNotConfiguredClient.class);
        });
    }

    @Test void everywhereElseTheOperatorIsNotConfigured() {
        String[][] cases = {{"app.deployment-environment=dev"}, {"app.deployment-environment=dev", "app.test-accounts.identity=local"},
                {"app.deployment-environment=prod"}, {"app.deployment-environment=prod", "app.test-accounts.identity=dev"}, {}};
        for (String[] properties : cases) context(properties).run(context -> {
            assertThat(context).as(String.join(",", properties)).hasNotFailed().doesNotHaveBean(IdentityOperatorClient.class);
            assertThat(context.getBean(TestAccountModeService.class).cloud()).isFalse();
            assertThat(context.getBeanNamesForType(TestAccountSignInClient.class)).containsExactly("testAccountSignInNotConfiguredClient");
            assertThat(context.getBean(TestAccountSignInClient.class)).isInstanceOf(TestAccountSignInNotConfiguredClient.class);
            assertThat(context.getBeanNamesForType(AccountSignInClient.class)).containsExactly("accountSignInNotConfiguredClient");
            assertThat(context.getBean(AccountSignInClient.class)).isInstanceOf(AccountSignInNotConfiguredClient.class);
        });
    }
}
