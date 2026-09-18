package com.lookahead.domain.security;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class IdentityVerificationClientTest {
    @Test void postsTokenOnlyToFixedEndpointWithSeparateServiceCredentials() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var settings = settings(server);
        server.createContext("/internal/v1/tokens/verify", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString(("lookahead-domain-verifier:" + settings.verifierSecret()).getBytes(StandardCharsets.ISO_8859_1)));
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("token=synthetic%2Btoken%26value");
            byte[] body = "{\"active\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new IdentityVerificationClient(settings, new ObjectMapper());
            assertThat(client.verify("synthetic+token&value").active()).isFalse();
            assertThat(client.verify("synthetic+token&value").active()).isFalse();
            assertThat(calls).hasValue(2);
        } finally { server.stop(0); }
    }
    @Test void redirectErrorsMalformedAndOversizedRepliesAreUnavailable() throws Exception {
        for (int scenario=0; scenario<5; scenario++) {
            int selected = scenario;
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var redirected = new AtomicInteger();
            server.createContext("/forbidden", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
            server.createContext("/internal/v1/tokens/verify", exchange -> {
                String value = switch (selected) {
                    case 2 -> "{\"active\":\"true\"}";
                    case 3 -> "{\"active\":true,\"subject\":\"unknown\"}";
                    case 4 -> " ".repeat(17 * 1024);
                    default -> "{}";
                };
                if (selected == 0) exchange.getResponseHeaders().set("Location", "/forbidden");
                byte[] body = value.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(selected == 0 ? 302 : selected == 1 ? 503 : 200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
            try {
                var client = new IdentityVerificationClient(settings(server), new ObjectMapper());
                assertThatThrownBy(() -> client.verify("secret-token")).isInstanceOf(IdentityUnavailableException.class)
                        .hasMessageNotContaining("secret-token").hasNoCause();
                assertThat(redirected).hasValue(0);
            } finally { server.stop(0); }
        }
    }
    private IdentitySettings settings(HttpServer server) {
        return new IdentitySettings("http://127.0.0.1:4380", "http://127.0.0.1:" + server.getAddress().getPort(),
                "lookahead-web-gateway", "synthetic-verifier-secret-for-unit-tests", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
