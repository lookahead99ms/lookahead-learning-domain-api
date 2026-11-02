package com.lookahead.domain.testaccounts;

import com.lookahead.domain.cloud.CognitoSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Active only with {@code app.test-accounts.identity=cognito}; the settings refuse every environment but dev.
 * The task-role Cognito client lives inside the operator so the anonymous token-check client stays the single
 * {@code CognitoIdentityProviderClient} bean the verifier injects.
 */
@Configuration
@ConditionalOnProperty(name = "app.test-accounts.identity", havingValue = "cognito")
public class CloudTestAccountConfiguration {
    @Bean CloudTestAccountSettings cloudTestAccountSettings(Environment environment, ObjectProvider<CognitoSettings> cognito) {
        return CloudTestAccountSettings.from(environment, cognito.getIfAvailable());
    }
    @Bean CognitoTestIdentityOperator cognitoTestIdentityOperator(CloudTestAccountSettings settings, CognitoSettings cognito) {
        return new CognitoTestIdentityOperator(settings, CognitoTestIdentityOperator.taskRoleClient(cognito.region()));
    }
}
