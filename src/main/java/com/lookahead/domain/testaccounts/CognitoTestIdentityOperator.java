package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.exception.AccountFailure;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

/**
 * DLV-969 phase C (DEV only): created test learners are users of the DEV sign-in pool, written with
 * the task role (ContainerCredentialsProvider), never with the anonymous token-check client.
 * The task role needs exactly five actions on the pool ARN: AdminCreateUser, AdminSetUserPassword,
 * AdminGetUser, AdminDisableUser, AdminDeleteUser.
 *
 * provision: AdminCreateUser (message suppressed, no attributes: the pool requires none and no email is
 * invented) + AdminSetUserPassword (permanent) + AdminGetUser for the subject. A user left behind by an
 * earlier attempt (UsernameExistsException) is reused, so a retry with the same mutation key completes.
 * activate: nothing to do; the password is already permanent.
 * disable (the Delete action): AdminDisableUser then AdminDeleteUser, so the user can never sign in again.
 * The Domain row is kept by design: the runtime role may not change cloud_accounts.enabled and the V17
 * trigger refuses to delete an admitted row, so {@link CreatedTestAccounts} revokes the account's
 * cloud_sign_ins instead and marks the registry row deleted exactly as in Local.
 * Every provider failure is the same 503 as the Local operator; a vanished user on disable is already done.
 */
public class CognitoTestIdentityOperator implements TestIdentity {
    private final CloudTestAccountSettings settings;
    private final CognitoIdentityProviderClient client;

    public CognitoTestIdentityOperator(CloudTestAccountSettings settings, CognitoIdentityProviderClient client) {
        this.settings = settings; this.client = client;
    }

    /** Same timeouts and no-retry style as the token-check client; credentials come from the task role. */
    static CognitoIdentityProviderClient taskRoleClient(String region) {
        return CognitoIdentityProviderClient.builder().region(Region.of(region))
                .credentialsProvider(ContainerCredentialsProvider.builder().build())
                .endpointOverride(URI.create("https://cognito-idp." + region + ".amazonaws.com"))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3))
                        .retryPolicy(RetryPolicy.none()))
                .build();
    }

    public String issuer() { return settings.issuer(); }

    @Override public String provision(UUID accountId, String username, String password) {
        try {
            try {
                client.adminCreateUser(AdminCreateUserRequest.builder().userPoolId(settings.poolId()).username(username)
                        .messageAction(MessageActionType.SUPPRESS).build());
            } catch (UsernameExistsException earlierAttempt) {
                // Retry-safe: the user exists from an attempt whose Domain transaction did not complete.
            }
            client.adminSetUserPassword(AdminSetUserPasswordRequest.builder().userPoolId(settings.poolId()).username(username)
                    .password(password).permanent(true).build());
            var user = client.adminGetUser(AdminGetUserRequest.builder().userPoolId(settings.poolId()).username(username).build());
            return user.userAttributes().stream().filter(a -> "sub".equals(a.name())).map(AttributeType::value)
                    .filter(v -> v != null && !v.isBlank()).findFirst().orElseThrow(TestIdentityOperator::unavailable);
        } catch (AccountFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw TestIdentityOperator.unavailable(); }
    }

    @Override public void activate(UUID accountId, String username) {
        // The permanent password set during provisioning is the activation; nothing to call.
    }

    @Override public void disable(UUID accountId, String username) {
        try {
            try {
                client.adminDisableUser(AdminDisableUserRequest.builder().userPoolId(settings.poolId()).username(username).build());
            } catch (UserNotFoundException gone) { return; }
            try {
                client.adminDeleteUser(AdminDeleteUserRequest.builder().userPoolId(settings.poolId()).username(username).build());
            } catch (UserNotFoundException gone) { /* deleted by an earlier attempt */ }
        } catch (RuntimeException failure) { throw TestIdentityOperator.unavailable(); }
    }
}
