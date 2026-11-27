package com.lookahead.domain.local.identity;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class IdentityOperatorClientTest {
    @Test void sendsOnlyFixedOperatorRoutesWithDedicatedCredentials() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new ArrayList<String>();
        server.createContext("/internal/v1/test-accounts/",exchange->{
            seen.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
            String expected="Basic "+Base64.getEncoder().encodeToString(
                    "lookahead-test-account-operator:synthetic-operator-secret-32-characters".getBytes(StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(expected);
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(body).contains("test-0123456789abcdef01234567");
            exchange.sendResponseHeaders(200,-1);exchange.close();
        });
        server.start();
        try {
            var connection=new IdentityConnection("http://127.0.0.1:"+server.getAddress().getPort(),
                    "http://127.0.0.1:"+server.getAddress().getPort(),"gateway","other-verifier-secret-32-characters",
                    Duration.ofSeconds(2),Duration.ofSeconds(2));
            var environment=new MockEnvironment().withProperty("app.test-accounts.operator-secret",
                    "synthetic-operator-secret-32-characters");
            var operator=new IdentityOperatorClient(connection,new IdentityConfiguration().identityRestClient(connection),environment);
            UUID id=UUID.randomUUID();String name="test-0123456789abcdef01234567";
            operator.provision(id,name,"synthetic-learner-fixture-only");
            operator.activate(id,name);
            operator.disable(id,name);
            assertThat(seen).containsExactly("POST /internal/v1/test-accounts/provision",
                    "POST /internal/v1/test-accounts/activate","POST /internal/v1/test-accounts/disable");
        } finally {server.stop(0);}
    }
    /** The Local answer without a usable operator secret: no call leaves, one upstream line, 503. */
    @Test void withoutAnOperatorSecretOf32CharactersNoCallLeavesAndTheAnswerIs503() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new ArrayList<String>();
        server.createContext("/",exchange->{seen.add(exchange.getRequestURI().getPath());exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
        try (var log=LogCapture.start()) {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();
            var connection=new IdentityConnection(origin,origin,"gateway","other-verifier-secret-32-characters",Duration.ofSeconds(2),Duration.ofSeconds(2));
            for (var environment : new MockEnvironment[] {new MockEnvironment(), new MockEnvironment().withProperty("app.test-accounts.operator-secret","x".repeat(31))}) {
                var operator=new IdentityOperatorClient(connection,new IdentityConfiguration().identityRestClient(connection),environment);
                assertThatThrownBy(()->operator.disableReal(UUID.randomUUID())).isInstanceOfSatisfying(AccountFailure.class,refused->{
                    assertThat(refused.status()).isEqualTo(503);
                    assertThat(refused.code()).isEqualTo("TEST_IDENTITY_UNAVAILABLE");
                    assertThat(refused.getMessage()).isEqualTo("Identity did not confirm test-account maintenance. Retry the same action.");
                });
            }
            assertThat(seen).isEmpty();
            assertThat(log.messages()).hasSize(2).allSatisfy(line->assertThat(line).matches("Identity TestAccount\\.disable-real refused in \\d+ ms: OPERATOR_NOT_CONFIGURED"));
        } finally {server.stop(0);}
    }
}
