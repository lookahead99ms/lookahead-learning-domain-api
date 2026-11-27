package com.lookahead.domain.local.identity;

import com.lookahead.domain.client.AccountSignInClient;
import com.lookahead.domain.client.TestAccountSignInClient;
import com.lookahead.domain.log.EventLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Local-only operator boundary; credentials never reach a browser response. DLV-1016: a bean of
 * {@link IdentityConfiguration}, so it exists only in Local; the other environments get the stand-ins of
 * {@code config.AccountSignInConfiguration}. With an operator secret shorter than 32 characters a call writes the
 * upstream line OPERATOR_NOT_CONFIGURED and answers 503 TEST_IDENTITY_UNAVAILABLE.
 */
public class IdentityOperatorClient implements TestAccountSignInClient, AccountSignInClient {
    private final RestClient client;
    private final String upstream;
    private final String secret;
    IdentityOperatorClient(IdentityConnection identity, RestClient http, Environment environment) {
        this.upstream = identity.upstream();
        this.secret = environment.getProperty("app.test-accounts.operator-secret", "");
        this.client = http;
    }
    @Override public String provision(UUID id, String username, String password) {
        call("provision", Map.of("accountId", id, "username", username, "password", password));
        return null;
    }
    @Override public void activate(UUID id, String username) { call("activate", Map.of("accountId", id, "username", username)); }
    @Override public void disable(UUID id, String username) { call("disable", Map.of("accountId", id, "username", username)); }
    @Override public void disableReal(UUID id) { call("disable-real", Map.of("accountId", id)); }
    private void call(String operation, Map<String, ?> body) {
        long started = EventLog.started();
        String name = "TestAccount." + operation;
        if (secret.length() < 32) {
            EventLog.upstreamRefused("Identity", name, started, "OPERATOR_NOT_CONFIGURED");
            throw TestAccountSignInClient.unavailable();
        }
        try {
            client.post().uri(upstream + "/internal/v1/test-accounts/" + operation)
                    .headers(headers -> headers.setBasicAuth("lookahead-test-account-operator", secret))
                    .body(body).retrieve().toBodilessEntity();
            EventLog.upstreamDone("Identity", name, started);
        } catch (RuntimeException failure) {
            EventLog.upstreamFailed("Identity", name, started, failure);
            throw TestAccountSignInClient.unavailable();
        }
    }
}
