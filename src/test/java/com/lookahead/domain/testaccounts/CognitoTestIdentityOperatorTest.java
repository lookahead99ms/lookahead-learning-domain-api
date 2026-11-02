package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.exception.AccountFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockMakers;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CognitoTestIdentityOperatorTest {
    static final CloudTestAccountSettings SETTINGS = new CloudTestAccountSettings("us-east-2_Example",
            "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example");
    final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final CognitoTestIdentityOperator operator = new CognitoTestIdentityOperator(SETTINGS, client);
    final UUID id = UUID.randomUUID();
    final String username = "test-0123456789abcdef01234567";

    static AdminGetUserResponse user(String sub) {
        var response = AdminGetUserResponse.builder().username("test-0123456789abcdef01234567")
                .userAttributes(AttributeType.builder().name("name").value("ignored").build());
        if (sub != null) response.userAttributes(AttributeType.builder().name("sub").value(sub).build(),
                AttributeType.builder().name("name").value("ignored").build());
        return response.build();
    }

    @Test void provisionCreatesSuppressedUserWithoutAttributesSetsPermanentPasswordAndReturnsSubject() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(user("subject-1"));
        assertThat(operator.provision(id, username, "Synthetic-learner-password-1!")).isEqualTo("subject-1");
        var create = ArgumentCaptor.forClass(AdminCreateUserRequest.class);
        var password = ArgumentCaptor.forClass(AdminSetUserPasswordRequest.class);
        var read = ArgumentCaptor.forClass(AdminGetUserRequest.class);
        InOrder order = inOrder(client);
        order.verify(client).adminCreateUser(create.capture());
        order.verify(client).adminSetUserPassword(password.capture());
        order.verify(client).adminGetUser(read.capture());
        assertThat(create.getValue().userPoolId()).isEqualTo("us-east-2_Example");
        assertThat(create.getValue().username()).isEqualTo(username);
        assertThat(create.getValue().messageAction()).isEqualTo(MessageActionType.SUPPRESS);
        assertThat(create.getValue().userAttributes()).isEmpty();
        assertThat(create.getValue().temporaryPassword()).isNull();
        assertThat(password.getValue().userPoolId()).isEqualTo("us-east-2_Example");
        assertThat(password.getValue().username()).isEqualTo(username);
        assertThat(password.getValue().password()).isEqualTo("Synthetic-learner-password-1!");
        assertThat(password.getValue().permanent()).isTrue();
        assertThat(read.getValue().username()).isEqualTo(username);
        assertThat(operator.issuer()).isEqualTo(SETTINGS.issuer());
        verifyNoMoreInteractions(client);
    }
    @Test void existingUserFromAnEarlierAttemptIsReusedAndItsSubjectRead() {
        when(client.adminCreateUser(any(AdminCreateUserRequest.class))).thenThrow(UsernameExistsException.builder().message("exists").build());
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(user("subject-retry"));
        assertThat(operator.provision(id, username, "Synthetic-learner-password-1!")).isEqualTo("subject-retry");
        verify(client).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
        verify(client).adminGetUser(any(AdminGetUserRequest.class));
    }
    @Test void providerFailuresAndAMissingSubjectAnswerTheSameRetryableUnavailable() {
        when(client.adminCreateUser(any(AdminCreateUserRequest.class))).thenThrow(SdkClientException.create("connection timed out"));
        assertUnavailable(() -> operator.provision(id, username, "Synthetic-learner-password-1!"));
        reset(client);
        when(client.adminSetUserPassword(any(AdminSetUserPasswordRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("denied").statusCode(403).build());
        assertUnavailable(() -> operator.provision(id, username, "Synthetic-learner-password-1!"));
        reset(client);
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(user(null));
        assertUnavailable(() -> operator.provision(id, username, "Synthetic-learner-password-1!"));
        reset(client);
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenThrow(new IllegalStateException("client closed"));
        assertUnavailable(() -> operator.provision(id, username, "Synthetic-learner-password-1!"));
    }
    @Test void activateCallsNothingBecauseThePasswordIsAlreadyPermanent() {
        operator.activate(id, username);
        verifyNoInteractions(client);
    }
    @Test void disableDisablesThenDeletesTheUserAndTreatsAVanishedUserAsDone() {
        operator.disable(id, username);
        var disable = ArgumentCaptor.forClass(AdminDisableUserRequest.class);
        var delete = ArgumentCaptor.forClass(AdminDeleteUserRequest.class);
        InOrder order = inOrder(client);
        order.verify(client).adminDisableUser(disable.capture());
        order.verify(client).adminDeleteUser(delete.capture());
        assertThat(disable.getValue().userPoolId()).isEqualTo("us-east-2_Example");
        assertThat(disable.getValue().username()).isEqualTo(username);
        assertThat(delete.getValue().username()).isEqualTo(username);
        reset(client);
        when(client.adminDisableUser(any(AdminDisableUserRequest.class))).thenThrow(UserNotFoundException.builder().build());
        assertThatCode(() -> operator.disable(id, username)).doesNotThrowAnyException();
        verify(client, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
        reset(client);
        when(client.adminDeleteUser(any(AdminDeleteUserRequest.class))).thenThrow(UserNotFoundException.builder().build());
        assertThatCode(() -> operator.disable(id, username)).doesNotThrowAnyException();
        reset(client);
        when(client.adminDeleteUser(any(AdminDeleteUserRequest.class))).thenThrow(SdkClientException.create("timeout"));
        assertUnavailable(() -> operator.disable(id, username));
        reset(client);
        when(client.adminDisableUser(any(AdminDisableUserRequest.class))).thenThrow(CognitoIdentityProviderException.builder().message("denied").build());
        assertUnavailable(() -> operator.disable(id, username));
        verify(client, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }
    @Test void taskRoleClientUsesContainerCredentialsInTheConfiguredRegion() {
        try (var built = CognitoTestIdentityOperator.taskRoleClient("us-east-2")) {
            var configuration = built.serviceClientConfiguration();
            assertThat(configuration.region().id()).isEqualTo("us-east-2");
            assertThat(configuration.credentialsProvider()).isInstanceOf(ContainerCredentialsProvider.class);
            assertThat(configuration.endpointOverride()).hasValueSatisfying(uri -> assertThat(uri.toString()).isEqualTo("https://cognito-idp.us-east-2.amazonaws.com"));
            assertThat(configuration.overrideConfiguration().retryPolicy()).hasValueSatisfying(policy -> assertThat(policy.numRetries()).isZero());
        }
    }
    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(503);
            assertThat(failure.code()).isEqualTo("TEST_IDENTITY_UNAVAILABLE");
            assertThat(failure.getMessage()).isEqualTo("Identity did not confirm test-account maintenance. Retry the same action.");
        });
    }
}
