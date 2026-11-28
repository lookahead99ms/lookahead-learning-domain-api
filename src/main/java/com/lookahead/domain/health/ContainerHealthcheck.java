package com.lookahead.domain.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container readiness probe, measured with the application's production code. */
// Exception, decided 2026-11-25 (DLV-1016): this is the stand-alone probe program of the container
// health check. The
// Dockerfile compiles this one file on its own and the health check starts it in its own JVM (`java
// -cp
// /opt/lookahead/health`), without Spring and without the application's classes, so there is no
// client bean it could
// use: it makes its own HTTP call to the application's loopback readiness address.
public final class ContainerHealthcheck {
  static final URI READINESS_URI = URI.create("http://127.0.0.1:8080/actuator/health/readiness");

  private ContainerHealthcheck() {}

  public static void main(String[] args) {
    System.exit(
        check(
            "lower-cost-dev".equals(System.getenv("LOOKAHEAD_RUNTIME_MODE"))
                ? URI.create("http://127.0.0.1:8081/actuator/health/readiness")
                : READINESS_URI));
  }

  static int check(URI endpoint) {
    return check(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), endpoint);
  }

  static int check(HttpClient client, URI endpoint) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(endpoint).timeout(Duration.ofSeconds(3)).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200
              && response.body().matches("\\s*\\{\\s*\"status\"\\s*:\\s*\"UP\"\\s*}\\s*")
          ? 0
          : 1;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return 1;
    } catch (IOException | RuntimeException exception) {
      return 1;
    }
  }
}
