package com.lookahead.domain.config;

import com.lookahead.domain.client.AccountSignInClient;
import com.lookahead.domain.client.AccountSignInNotConfiguredClient;
import com.lookahead.domain.client.TestAccountNoSignInClient;
import com.lookahead.domain.client.TestAccountSignInClient;
import com.lookahead.domain.client.TestAccountSignInNotConfiguredClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The sign-in clients of account maintenance outside Local. DLV-1016: which client an environment gets is decided by
 * the conditions on the bean methods, not inside a service. Local has one bean for both interfaces, the Identity
 * operator ({@code local.identity.IdentityConfiguration}). Everywhere else:
 * {@link TestAccountSignInClient} is {@link TestAccountNoSignInClient} for DEV's database-only test accounts (the
 * condition of {@code TestAccountModeService.cloud()}) and {@link TestAccountSignInNotConfiguredClient} otherwise;
 * {@link AccountSignInClient} is {@link AccountSignInNotConfiguredClient}.
 */
@Configuration
public class AccountSignInConfiguration {
    static final String NOT_LOCAL = "'${app.deployment-environment:}' != 'local'";
    /** {@code TestAccountModeService.cloud()}: DEV with {@code app.test-accounts.identity=dev}. */
    static final String CLOUD = "'${app.deployment-environment:}' == 'dev' && '${app.test-accounts.identity:local}' == 'dev'";
    static final String NOT_LOCAL_NOT_CLOUD = NOT_LOCAL + " && !(" + CLOUD + ")";

    @Bean @ConditionalOnExpression(CLOUD)
    TestAccountSignInClient testAccountNoSignInClient() { return new TestAccountNoSignInClient(); }

    @Bean @ConditionalOnExpression(NOT_LOCAL_NOT_CLOUD)
    TestAccountSignInClient testAccountSignInNotConfiguredClient() { return new TestAccountSignInNotConfiguredClient(); }

    @Bean @ConditionalOnExpression(NOT_LOCAL)
    AccountSignInClient accountSignInNotConfiguredClient() { return new AccountSignInNotConfiguredClient(); }
}
