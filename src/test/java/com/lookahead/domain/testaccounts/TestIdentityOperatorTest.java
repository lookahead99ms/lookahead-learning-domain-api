package com.lookahead.domain.testaccounts;

import com.lookahead.domain.security.IdentitySettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestIdentityOperatorTest {
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
            @SuppressWarnings("unchecked") ObjectProvider<IdentitySettings> settings=mock(ObjectProvider.class);
            doReturn(new IdentitySettings("http://127.0.0.1:"+server.getAddress().getPort(),
                    "http://127.0.0.1:"+server.getAddress().getPort(),"gateway","other-verifier-secret-32-characters",
                    Duration.ofSeconds(2),Duration.ofSeconds(2))).when(settings).getIfAvailable();
            var environment=mock(Environment.class);
            when(environment.getProperty("app.test-accounts.operator-secret", ""))
                    .thenReturn("synthetic-operator-secret-32-characters");
            var operator=new TestIdentityOperator(settings,environment);
            UUID id=UUID.randomUUID();String name="test-0123456789abcdef01234567";
            operator.provision(id,name,"synthetic-learner-fixture-only");
            operator.activate(id,name);
            operator.disable(id,name);
            assertThat(seen).containsExactly("POST /internal/v1/test-accounts/provision",
                    "POST /internal/v1/test-accounts/activate","POST /internal/v1/test-accounts/disable");
        } finally {server.stop(0);}
    }
}
