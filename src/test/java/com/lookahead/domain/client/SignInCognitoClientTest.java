package com.lookahead.domain.client;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.config.SignInSecurityConfiguration;
import com.lookahead.domain.security.token.SignInTokenVerifierTest;
import com.lookahead.domain.exception.AccountFailure;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-969: Disable, Enable and Delete call exactly the issuer's pool, are safe to repeat, and fail as one retryable 503. */
class SignInCognitoClientTest {
    // Subclass mock maker: the image build runs without the inline agent.
    final CognitoIdentityProviderClient client=mock(CognitoIdentityProviderClient.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final CognitoIdentityProviderClient userClient=mock(CognitoIdentityProviderClient.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final SignInCognitoClient accounts=new SignInCognitoClient(SignInTokenVerifierTest.TRUST,client,userClient);

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
        try (var built=new SignInSecurityConfiguration().cognitoTaskRoleClient(SignInTokenVerifierTest.TRUST)) {
            assertThat(built.serviceClientConfiguration().region().id()).isEqualTo("us-east-2");
        }
    }

    /** DLV-969: one lookahead.upstream line per Cognito call; a failure names its type, status and code, never the username. */
    @Test void everyPoolCallIsOneUpstreamLineWithoutTheUsername() {
        try (var log = LogCapture.start()) {
            accounts.disable("google_123");
            assertThat(log.lines("upstream.call")).extracting(line -> LogCapture.keys(line).get("upstream.operation"))
                    .containsExactly("AdminDisableUser", "AdminUserGlobalSignOut");
            assertThat(log.messages()).allMatch(message -> message.matches("Cognito Admin\\w+ succeeded in \\d+ ms"));

            when(client.adminEnableUser(any(AdminEnableUserRequest.class))).thenThrow(NotAuthorizedException.builder().message("denied for google_123")
                    .statusCode(400).awsErrorDetails(AwsErrorDetails.builder()
                            .errorCode("NotAuthorizedException").errorMessage("denied for google_123").build()).build());
            assertThatThrownBy(() -> accounts.enable("google_123")).isInstanceOf(AccountFailure.class);
            var failed = log.lines().getLast();
            assertThat(failed.getLevel()).isEqualTo(Level.WARN);
            assertThat(failed.getLoggerName()).isEqualTo("lookahead.upstream");
            assertThat(failed.getFormattedMessage()).matches("Cognito AdminEnableUser failed in \\d+ ms: NotAuthorizedException \\(HTTP 400, NotAuthorizedException\\)");
            assertThat(LogCapture.keys(failed)).containsEntry("event.outcome", "failure").containsEntry("upstream.service", "cognito")
                    .containsEntry("upstream.status", 400).containsEntry("upstream.code", "NotAuthorizedException")
                    .containsEntry("error.type", "NotAuthorizedException").containsKey("durationMs");

            when(client.adminDeleteUser(any(AdminDeleteUserRequest.class))).thenThrow(UserNotFoundException.builder().message("gone").build());
            accounts.remove("google_123");
            assertThat(LogCapture.keys(log.lines().getLast())).containsEntry("event.outcome", "refused").containsEntry("reason", "USER_ALREADY_GONE");
            log.containsNone("google_123", "denied");
        }
    }

    /** The person's own password change and sign-out go out on the connection without AWS credentials, never the task-role one. */
    @SuppressWarnings("unchecked")
    @Test void thePasswordChangeAndTheSignOutUseThePersonsOwnTokenConnection() {
        accounts.changePassword("synthetic-access-token","current-password-123","a-new-password-456");
        accounts.globalSignOut("synthetic-access-token");
        verify(userClient).changePassword(any(Consumer.class));
        verify(userClient).globalSignOut(any(Consumer.class));
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    @Test void aRefusedPasswordChangeSaysWhyAndAnUnconfirmedOneAsksToSignInAgain() {
        when(userClient.changePassword(any(Consumer.class)))
                .thenThrow(NotAuthorizedException.builder().message("no").build())
                .thenThrow(InvalidPasswordException.builder().message("weak").build())
                .thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->accounts.changePassword("t","old","new")).isInstanceOfSatisfying(AccountFailure.class,f->{
            assertThat(f.status()).isEqualTo(401);assertThat(f.code()).isEqualTo("INVALID_CURRENT_PASSWORD");});
        assertThatThrownBy(()->accounts.changePassword("t","old","new")).isInstanceOfSatisfying(AccountFailure.class,f->{
            assertThat(f.status()).isEqualTo(422);assertThat(f.code()).isEqualTo("INVALID_PASSWORD");});
        assertThatThrownBy(()->accounts.changePassword("t","old","new")).isInstanceOfSatisfying(AccountFailure.class,f->{
            assertThat(f.status()).isEqualTo(503);assertThat(f.code()).isEqualTo("SERVICE_UNAVAILABLE");});
        when(userClient.globalSignOut(any(Consumer.class))).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->accounts.globalSignOut("t")).isInstanceOfSatisfying(AccountFailure.class,f->{
            assertThat(f.status()).isEqualTo(503);assertThat(f.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(f.getMessage()).contains("provider sign-out could not be confirmed");});
    }
}
