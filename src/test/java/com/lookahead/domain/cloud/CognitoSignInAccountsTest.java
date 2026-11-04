package com.lookahead.domain.cloud;

import com.lookahead.learning.content.exception.AccountFailure;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-969: Disable, Enable and Delete call exactly the issuer's pool, are safe to repeat, and fail as one retryable 503. */
class CognitoSignInAccountsTest {
    // Subclass mock maker, as in CognitoTestIdentityOperatorTest: the image build runs without the inline agent.
    final CognitoIdentityProviderClient client=mock(CognitoIdentityProviderClient.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final CognitoSignInAccounts accounts=new CognitoSignInAccounts(CognitoTokenVerifierTest.SETTINGS,client);

    @Test void eachActionNamesTheIssuersPoolAndTheUser() {
        assertThat(accounts.poolId()).isEqualTo("us-east-2_Example");
        accounts.disable("google_123");
        verify(client).adminDisableUser(AdminDisableUserRequest.builder().userPoolId("us-east-2_Example").username("google_123").build());
        verify(client).adminUserGlobalSignOut(AdminUserGlobalSignOutRequest.builder().userPoolId("us-east-2_Example").username("google_123").build());
        accounts.enable("google_123");
        verify(client).adminEnableUser(AdminEnableUserRequest.builder().userPoolId("us-east-2_Example").username("google_123").build());
        accounts.remove("google_123");
        verify(client).adminDeleteUser(AdminDeleteUserRequest.builder().userPoolId("us-east-2_Example").username("google_123").build());
    }
    @Test void aUserAlreadyGoneIsDoneAndAnyOtherFailureAsksForARetry() {
        when(client.adminDeleteUser(any(AdminDeleteUserRequest.class))).thenThrow(UserNotFoundException.builder().message("gone").build());
        assertThatCode(()->accounts.remove("google_123")).doesNotThrowAnyException();
        when(client.adminDisableUser(any(AdminDisableUserRequest.class))).thenThrow(UserNotFoundException.builder().message("gone").build());
        assertThatCode(()->accounts.disable("google_123")).doesNotThrowAnyException();
        verify(client,never()).adminUserGlobalSignOut(any(AdminUserGlobalSignOutRequest.class));
        when(client.adminEnableUser(any(AdminEnableUserRequest.class))).thenThrow(NotAuthorizedException.builder().message("denied").build());
        assertThatThrownBy(()->accounts.enable("google_123")).isInstanceOfSatisfying(AccountFailure.class,f->{
            assertThat(f.status()).isEqualTo(503);assertThat(f.code()).isEqualTo("SIGN_IN_PROVIDER_UNAVAILABLE");});
    }
    @Test void theTaskRoleClientIsBuiltForTheRegion() {
        try (var built=CognitoSignInAccounts.taskRoleClient("us-east-2")) {
            assertThat(built.serviceClientConfiguration().region().id()).isEqualTo("us-east-2");
        }
    }

    /** DLV-969: one lookahead.upstream line per Cognito call; a failure names its type, status and code, never the username. */
    @Test void everyPoolCallIsOneUpstreamLineWithoutTheUsername() {
        try (var log = com.lookahead.domain.LogCapture.start()) {
            accounts.disable("google_123");
            assertThat(log.lines("upstream.call")).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("upstream.operation"))
                    .containsExactly("AdminDisableUser", "AdminUserGlobalSignOut");
            assertThat(log.messages()).allMatch(message -> message.matches("Cognito Admin\\w+ succeeded in \\d+ ms"));

            when(client.adminEnableUser(any(AdminEnableUserRequest.class))).thenThrow(NotAuthorizedException.builder().message("denied for google_123")
                    .statusCode(400).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                            .errorCode("NotAuthorizedException").errorMessage("denied for google_123").build()).build());
            assertThatThrownBy(() -> accounts.enable("google_123")).isInstanceOf(AccountFailure.class);
            var failed = log.lines().getLast();
            assertThat(failed.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(failed.getLoggerName()).isEqualTo("lookahead.upstream");
            assertThat(failed.getFormattedMessage()).matches("Cognito AdminEnableUser failed in \\d+ ms: NotAuthorizedException \\(HTTP 400, NotAuthorizedException\\)");
            assertThat(com.lookahead.domain.LogCapture.keys(failed)).containsEntry("event.outcome", "failure").containsEntry("upstream.service", "cognito")
                    .containsEntry("upstream.status", 400).containsEntry("upstream.code", "NotAuthorizedException")
                    .containsEntry("error.type", "NotAuthorizedException").containsKey("durationMs");

            when(client.adminDeleteUser(any(AdminDeleteUserRequest.class))).thenThrow(UserNotFoundException.builder().message("gone").build());
            accounts.remove("google_123");
            assertThat(com.lookahead.domain.LogCapture.keys(log.lines().getLast())).containsEntry("event.outcome", "refused").containsEntry("reason", "USER_ALREADY_GONE");
            log.containsNone("google_123", "denied");
        }
    }
}
