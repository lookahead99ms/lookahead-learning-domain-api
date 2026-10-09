package com.lookahead.domain.health;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContainerHealthcheckTest {
    @ParameterizedTest
    @CsvSource({"200,UP,0", "503,UP,1", "200,DOWN,1"})
    void requiresSuccessfulReadyResponse(int status, String state, int expected) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health/readiness", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            byte[] body = (" { \"status\" : \"" + state + "\" } \n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health/readiness");
            assertEquals(expected, ContainerHealthcheck.check(endpoint));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedOrExpandedStatusAndUsesBoundedGet() throws Exception {
        HttpClient client = mock(HttpClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        for (String body : new String[]{"UP", "{}", "{\"status\":\"UP\",\"extra\":true}", "{\"status\":\"UP\"}junk"}) {
            when(response.body()).thenReturn(body);
            assertEquals(1, ContainerHealthcheck.check(client, ContainerHealthcheck.READINESS_URI));
        }
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(4)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(URI.create("http://127.0.0.1:8080/actuator/health/readiness"), request.getValue().uri());
        assertEquals(Duration.ofSeconds(3), request.getValue().timeout().orElseThrow());
        assertEquals("GET", request.getValue().method());
    }

    @Test
    void connectionFailureIsUnhealthy() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health/readiness");
        server.start();
        server.stop(0);
        assertEquals(1, ContainerHealthcheck.check(endpoint));
    }

    @Test
    void interruptionIsUnhealthyAndPreservesCancellation() throws Exception {
        HttpClient client = mock(HttpClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("synthetic cancellation"));
        try {
            assertEquals(1, ContainerHealthcheck.check(client, ContainerHealthcheck.READINESS_URI));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidEndpointIsUnhealthy() {
        assertEquals(1, ContainerHealthcheck.check(HttpClient.newHttpClient(), URI.create("ftp://127.0.0.1/health")));
    }
}
