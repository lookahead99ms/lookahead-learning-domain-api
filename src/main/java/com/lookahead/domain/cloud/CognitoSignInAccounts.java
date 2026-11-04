package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

/**
 * DLV-969: the owner's Disable, Enable and Delete reach the sign-in pool through the task role
 * (ContainerCredentialsProvider), never the anonymous token-check client. The task role needs, on exactly the pool of
 * {@code app.cognito.issuer}: AdminDisableUser, AdminEnableUser, AdminUserGlobalSignOut, AdminDeleteUser (IAM #7).
 * Each call is safe to repeat; a user already gone counts as done. Any provider failure is one 503 that asks for a retry.
 */
public class CognitoSignInAccounts {
    private final CognitoIdentityProviderClient client;
    private final String poolId;

    public CognitoSignInAccounts(CognitoSettings settings, CognitoIdentityProviderClient client) {
        this.client = client;
        this.poolId = settings.issuer().substring(settings.issuer().lastIndexOf('/') + 1);
    }

    static CognitoIdentityProviderClient taskRoleClient(String region) {
        return CognitoIdentityProviderClient.builder().region(Region.of(region))
                .credentialsProvider(ContainerCredentialsProvider.builder().build())
                .endpointOverride(URI.create("https://cognito-idp." + region + ".amazonaws.com"))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3))
                        .retryPolicy(RetryPolicy.none()))
                .build();
    }

    String poolId() { return poolId; }

    /** No sign-in with email or Google, and every refresh token of the user is revoked. */
    public void disable(String username) {
        if (call("AdminDisableUser", () -> client.adminDisableUser(AdminDisableUserRequest.builder().userPoolId(poolId).username(username).build())))
            call("AdminUserGlobalSignOut", () -> client.adminUserGlobalSignOut(AdminUserGlobalSignOutRequest.builder().userPoolId(poolId).username(username).build()));
    }

    public void enable(String username) {
        call("AdminEnableUser", () -> client.adminEnableUser(AdminEnableUserRequest.builder().userPoolId(poolId).username(username).build()));
    }

    /** The user is gone; signing in again with the same email or Google account creates a new user with a new subject. */
    public void remove(String username) {
        call("AdminDeleteUser", () -> client.adminDeleteUser(AdminDeleteUserRequest.builder().userPoolId(poolId).username(username).build()));
    }

    /** One {@code lookahead.upstream} line per call (never the username). @return false when the user is already gone. */
    private static boolean call(String operation, Runnable action) {
        long started = Events.started();
        try { action.run(); Events.upstreamDone("Cognito", operation, started); return true; }
        catch (UserNotFoundException gone) {
            // Already deleted: nothing left to change, so the action counts as done.
            Events.upstreamRefused("Cognito", operation, started, "USER_ALREADY_GONE");
            return false;
        }
        catch (RuntimeException failure) {
            Events.upstreamFailed("Cognito", operation, started, failure);
            throw new AccountFailure(503, "SIGN_IN_PROVIDER_UNAVAILABLE", "Saved here, but the sign-in service did not confirm. Retry the same action.");
        }
    }
}
