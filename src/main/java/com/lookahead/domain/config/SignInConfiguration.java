package com.lookahead.domain.config;

import com.lookahead.domain.client.SignInPasswordClient;
import com.lookahead.domain.job.DevAccessCleanupJob;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.signin.SignInPasswordService;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.signin.DevAccessCleanupStore;
import com.lookahead.domain.db.store.signin.SignInStore;
import com.lookahead.domain.db.store.signin.SignIn;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The sign-in beans that are not security wiring: the store, the service on it, the password change and the DEV
 * access cleanup job. DEV and PROD only. DLV-1016: these four bean methods were in
 * {@code security.config.SignInSecurityConfiguration}, which keeps the token trust, the Cognito connections, the
 * decoder and the filter chain.
 */
@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
public class SignInConfiguration {
    @Bean SignInPasswordService signInPasswordService(SignInService signInService,SignInPasswordClient cognito){return new SignInPasswordService(signInService,cognito);}
    /** The sign-in store on DynamoDB. */
    @Bean SignIn signInStore(DynamoDbClient client,DynamoTableNames tables){return new SignInStore(client,tables);}
    /**
     * DLV-1016: the sign-in service (rules, refusals, log lines) on the store above. DLV-971: prepared access by email admits a
     * first contact; a context without the access service (configuration tests) keeps every first contact waiting. The service
     * takes the access service in its constructor (it had a setter that this method called; the store held it before that).
     */
    @Bean SignInService signInService(SignIn store,ObjectProvider<StaffAccessService> access){return new SignInService(store,access.getIfAvailable());}
    @Bean DevAccessCleanupJob devAccessCleanupJob(DynamoDbClient client,DynamoTableNames tables){return new DevAccessCleanupJob(new DevAccessCleanupStore(client,tables));}
}
