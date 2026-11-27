package com.lookahead.domain.client;

import com.lookahead.domain.util.Sha256;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import com.lookahead.domain.security.config.SignInTokenTrust;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.exception.AccountFailure;
import java.time.Duration;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

/**
 * DLV-969: the owner's Disable, Enable and Delete reach the sign-in pool through the task role
 * (ContainerCredentialsProvider), never the anonymous token-check client. The task role needs, on exactly the pool of
 * {@code app.cognito.issuer}: AdminDisableUser, AdminEnableUser, AdminUserGlobalSignOut, AdminDeleteUser (IAM #7).
 * Each call is safe to repeat; a user already gone counts as done. Any provider failure is one 503 that asks for a retry.
 */
public class SignInCognitoClient implements SignInPoolClient, SignInPasswordClient {
    /** The task-role connection: the owner's Disable, Enable and Delete. */
    private final CognitoIdentityProviderClient client;
    /** The connection without AWS credentials: calls made with the person's own access token. */
    private final CognitoIdentityProviderClient userClient;
    private final String poolId;
    private final Clock clock;
    /**
     * DLV-969 S37 (user, 2026-11-03): Cognito's answer for one access token is reused for up to a minute, never past the
     * token's expiry. Every claim check still runs on each request, and the Domain's own sign-in record (sign-out,
     * Disable, Delete) is still read on each request; only a revocation made inside Cognito alone waits up to a minute.
     */
    static final Duration REUSE=Duration.ofSeconds(60);
    static final int REUSE_LIMIT=10_000;
    public record Answer(String username,Map<String,String> attributes,Instant until) {}
    private final ConcurrentHashMap<String,Answer> answers=new ConcurrentHashMap<>();

    public SignInCognitoClient(SignInTokenTrust trust, CognitoIdentityProviderClient client, CognitoIdentityProviderClient userClient) {
        this(trust, client, userClient, Clock.systemUTC());
    }
    public SignInCognitoClient(SignInTokenTrust trust, CognitoIdentityProviderClient client, CognitoIdentityProviderClient userClient, Clock clock) {
        this.client = client;
        this.userClient = userClient;
        this.clock = clock;
        this.poolId = trust.issuer().substring(trust.issuer().lastIndexOf('/') + 1);
    }

    String poolId() { return poolId; }

    /** No sign-in with email or Google, and every refresh token of the user is revoked. */
    @Override public void disable(String username) {
        if (call("AdminDisableUser", () -> client.adminDisableUser(AdminDisableUserRequest.builder().userPoolId(poolId).username(username).build())))
            call("AdminUserGlobalSignOut", () -> client.adminUserGlobalSignOut(AdminUserGlobalSignOutRequest.builder().userPoolId(poolId).username(username).build()));
    }

    @Override public void enable(String username) {
        call("AdminEnableUser", () -> client.adminEnableUser(AdminEnableUserRequest.builder().userPoolId(poolId).username(username).build()));
    }

    /** The user is gone; signing in again with the same email or Google account creates a new user with a new subject. */
    @Override public void remove(String username) {
        call("AdminDeleteUser", () -> client.adminDeleteUser(AdminDeleteUserRequest.builder().userPoolId(poolId).username(username).build()));
    }

    /** One {@code lookahead.upstream} line per call (never the username). @return false when the user is already gone. */
    private static boolean call(String operation, Runnable action) {
        long started = EventLog.started();
        try { action.run(); EventLog.upstreamDone("Cognito", operation, started); return true; }
        catch (UserNotFoundException gone) {
            // Already deleted: nothing left to change, so the action counts as done.
            EventLog.upstreamRefused("Cognito", operation, started, "USER_ALREADY_GONE");
            return false;
        }
        catch (RuntimeException failure) {
            EventLog.upstreamFailed("Cognito", operation, started, failure);
            throw new AccountFailure(503, "SIGN_IN_PROVIDER_UNAVAILABLE", "Saved here, but the sign-in service did not confirm. Retry the same action.");
        }
    }

    /** Cognito refused the token: revoked, or its user is gone. DLV-1016: the caller no longer reads the SDK's exception types. */
    public static final class TokenRejected extends RuntimeException { TokenRejected(RuntimeException cause){super("Cognito rejected the access token",cause);} }
    /** Cognito's own failure while checking the token (no answer, throttling, a service error); its upstream line is already written. */
    public static final class TokenCheckUnavailable extends RuntimeException { TokenCheckUnavailable(RuntimeException cause){super("Cognito could not check the access token",cause);} }

    /** Cognito's GetUser for this token; with {@code reuse}, asked at most once a minute per token. Only successes are kept. */
    public Answer tokenUser(Jwt jwt,boolean reuse){
        String key=Sha256.hex(jwt.getTokenValue());
        Instant at=clock.instant();
        var kept=reuse?answers.get(key):null;
        if(kept!=null && at.isBefore(kept.until()))return kept;
        long started=EventLog.started();
        GetUserResponse user;
        try {user=userClient.getUser(r->r.accessToken(jwt.getTokenValue()));}
        catch(NotAuthorizedException|UserNotFoundException rejected){EventLog.upstreamRefused("Cognito","GetUser",started,"TOKEN_REJECTED");throw new TokenRejected(rejected);}
        catch(RuntimeException failure){EventLog.upstreamFailed("Cognito","GetUser",started,failure);throw failure instanceof SdkException sdk?new TokenCheckUnavailable(sdk):failure;}
        EventLog.upstreamDone("Cognito","GetUser",started);
        var attributes=user.userAttributes().stream().collect(Collectors.toMap(a->a.name(),a->a.value(),(a,b)->a));
        Instant until=at.plus(REUSE);
        if(jwt.getExpiresAt()!=null && jwt.getExpiresAt().isBefore(until))until=jwt.getExpiresAt();
        var answer=new Answer(user.username(),Map.copyOf(attributes),until);
        if(answers.size()>=REUSE_LIMIT)answers.entrySet().removeIf(entry->!at.isBefore(entry.getValue().until()));
        if(answers.size()<REUSE_LIMIT)answers.put(key,answer);
        return answer;
    }

    /** The person's own password change, with their access token. A wrong current password is 401, a refused new one 422, anything else 503. */
    @Override public void changePassword(String token, String old, String next) {
        long started=EventLog.started();
        try {
            userClient.changePassword(r->r.accessToken(token).previousPassword(old).proposedPassword(next));
            EventLog.upstreamDone("Cognito","ChangePassword",started);
        } catch(NotAuthorizedException rejected){EventLog.upstreamRefused("Cognito","ChangePassword",started,"INVALID_CURRENT_PASSWORD");throw new AccountFailure(401,"INVALID_CURRENT_PASSWORD","Current password could not be verified.");}
        catch(InvalidPasswordException rejected){EventLog.upstreamRefused("Cognito","ChangePassword",started,"INVALID_PASSWORD");throw new AccountFailure(422,"INVALID_PASSWORD","Password does not meet the account policy.");}
        catch(RuntimeException uncertain){EventLog.upstreamFailed("Cognito","ChangePassword",started,uncertain);throw new AccountFailure(503,"SERVICE_UNAVAILABLE","Password change or sign-out could not be confirmed. Try signing in before retrying.");}
    }

    /** Ends every sign-in of the person at the provider, with their access token; a failure is 503. */
    @Override public void globalSignOut(String token) {
        long started=EventLog.started();
        try {userClient.globalSignOut(r->r.accessToken(token));EventLog.upstreamDone("Cognito","GlobalSignOut",started);}
        catch(RuntimeException uncertain){EventLog.upstreamFailed("Cognito","GlobalSignOut",started,uncertain);throw new AccountFailure(503,"SERVICE_UNAVAILABLE","Password changed and application sign-ins ended, but provider sign-out could not be confirmed. Sign in again.");}
    }
}
