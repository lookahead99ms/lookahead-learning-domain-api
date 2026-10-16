package com.lookahead.domain.testaccounts;

import com.lookahead.domain.security.IdentitySettings;
import com.lookahead.learning.content.exception.AccountFailure;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Local-only operator boundary; credentials never reach a browser response. */
@Component
public class TestIdentityOperator {
    private final RestClient client;
    private final String upstream;
    private final String secret;
    TestIdentityOperator(ObjectProvider<IdentitySettings> settings, Environment environment) {
        var identity = settings.getIfAvailable();
        this.upstream = identity == null ? "" : identity.upstream();
        this.secret = environment.getProperty("app.test-accounts.operator-secret", "");
        if (identity == null) { client = null; return; }
        var http = HttpClient.newBuilder().connectTimeout(identity.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(identity.readTimeout());
        client = RestClient.builder().requestFactory(factory).build();
    }
    void provision(UUID id, String username, String password) {
        call("provision", Map.of("accountId", id, "username", username, "password", password));
    }
    void activate(UUID id, String username) { call("activate", Map.of("accountId", id, "username", username)); }
    void disable(UUID id, String username) { call("disable", Map.of("accountId", id, "username", username)); }
    private void call(String operation, Map<String, ?> body) {
        if (client == null || secret.length() < 32) throw unavailable();
        try {
            client.post().uri(upstream + "/internal/v1/test-accounts/" + operation)
                    .headers(headers -> headers.setBasicAuth("lookahead-test-account-operator", secret))
                    .body(body).retrieve().toBodilessEntity();
        } catch (RuntimeException failure) { throw unavailable(); }
    }
    private static AccountFailure unavailable() {
        return new AccountFailure(503, "TEST_IDENTITY_UNAVAILABLE", "Identity did not confirm test-account maintenance. Retry the same action.");
    }
}
