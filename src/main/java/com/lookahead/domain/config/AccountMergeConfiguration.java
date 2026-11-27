package com.lookahead.domain.config;

import com.lookahead.domain.db.store.access.AccountRemoval;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import com.lookahead.domain.client.EmailClient;
import com.lookahead.domain.client.EmailLoggingClient;
import com.lookahead.domain.client.EmailSesClient;
import com.lookahead.domain.service.signin.AccountMergeService;
import com.lookahead.domain.db.store.signin.AccountMergeStore;
import com.lookahead.domain.db.store.signin.AccountMerge;
import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.plan.StudyPlanFacts;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.validator.AccountMergeEmailConfigValidator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * DLV-969 S36: the merge of accounts that share a verified email, cloud mode only. SES sends the link only with
 * {@code LOOKAHEAD_EMAIL_ENABLED=true}, and then {@code LOOKAHEAD_EMAIL_FROM} and {@code AWS_REGION} are required (startup
 * fails naming the missing one); otherwise the email is only logged as skipped. {@code LOOKAHEAD_PUBLIC_URL} is the
 * origin of the link.
 */
@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
public class AccountMergeConfiguration {
    static final String EMAIL_ON="'${app.email.enabled:false}'.strip().equalsIgnoreCase('true')";
    static final String EMAIL_OFF="!'${app.email.enabled:false}'.strip().equalsIgnoreCase('true')";

    /** The checked email values, read once (DLV-1016: three bean methods used to read them each for itself). */
    @Bean AccountMergeEmailConfigValues accountMergeEmailConfigValues(Environment env){return AccountMergeEmailConfigValidator.validated(env);}
    /** The checked origin of the link; checked after the email values, as before. */
    @Bean AccountMergeLinkConfigValues accountMergeLinkConfigValues(Environment env){return new AccountMergeLinkConfigValues(AccountMergeEmailConfigValidator.publicUrl(env));}

    /** The SES connection with the task role. It is built only when the email is enabled: {@code emailClient} asks for it then. */
    @Bean(destroyMethod="") @Lazy public SesV2Client emailSesConnection(AccountMergeEmailConfigValues values){
        return AwsConnectionFactory.regional(SesV2Client.builder(),"email",values.region(),ContainerCredentialsProvider.builder().build());
    }
    /** The email is off: the link is only logged as skipped. DLV-1016: the condition chooses the client; both are the bean {@code emailClient}. */
    @Bean("emailClient") @ConditionalOnExpression(EMAIL_OFF) EmailClient emailLoggingClient(){return new EmailLoggingClient();}
    /** The email is on: SES with the task role. */
    @Bean("emailClient") @ConditionalOnExpression(EMAIL_ON) EmailClient emailSesClient(AccountMergeEmailConfigValues values,SesV2Client emailSesConnection){
        return new EmailSesClient(emailSesConnection,values.from());
    }
    /** DLV-970: the merge's units on DynamoDB (PostgreSQL retired 2026-11-17). */
    @Bean AccountMerge accountMergeStore(DynamoDbClient client,DynamoTableNames tables,
            ObjectProvider<TestAccountRegistry> testAccounts,ObjectProvider<MembershipFacts> memberships,ObjectProvider<StudyPlanFacts> plans){
        return new AccountMergeStore(client,tables,testAccounts,memberships,plans);
    }
    /** The merge service: the policy, the email and the log lines, on the store above. */
    @Bean AccountMergeService accountMergeService(AccountMerge store,AccountRemoval removal,EmailClient email,AccountMergeLinkConfigValues link){
        return new AccountMergeService(store,removal,email,link.publicUrl());
    }
}
