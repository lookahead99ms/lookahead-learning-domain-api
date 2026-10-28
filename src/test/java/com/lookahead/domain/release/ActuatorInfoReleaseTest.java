package com.lookahead.domain.release;

import com.lookahead.domain.security.DomainSecurityConfiguration;
import com.lookahead.domain.security.IdentityVerificationClient;
import com.lookahead.learning.content.repository.AccountRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** DLV-914: the running Domain reports its release through /actuator/info and exposes nothing else new. */
@SpringBootTest(classes = ActuatorInfoReleaseTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=actuator-info-release-test", "server.address=127.0.0.1", "app.deployment-environment=local",
                "app.identity.issuer=http://127.0.0.1:4380", "app.identity.upstream=http://identity:8080",
                "app.identity.gateway-client-id=lookahead-web-gateway", "app.identity.verifier-secret=synthetic-verifier-secret-for-unit-tests",
                // Same exposure as application.yml (asserted below); the real file needs a database and secrets.
                "management.endpoints.web.exposure.include=health,info"})
class ActuatorInfoReleaseTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @Import(DomainSecurityConfiguration.class)
    static class App {
        @Bean AccountRepository accounts() { return mock(AccountRepository.class, withSettings().mockMaker(MockMakers.SUBCLASS)); }
        @Bean @Primary JwtDecoder decoder() { return mock(JwtDecoder.class, withSettings().mockMaker(MockMakers.SUBCLASS)); }
        @Bean @Primary IdentityVerificationClient verifier() { return mock(IdentityVerificationClient.class, withSettings().mockMaker(MockMakers.SUBCLASS)); }
    }

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test void infoReportsBuildVersionReleaseTagAndSourceRevisionWithoutAuthentication() throws Exception {
        var response = send("GET", "/actuator/info");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode build = new ObjectMapper().readTree(response.body()).path("build");
        // Maven passes -Drevision (calendar version in a release build, 0.0.1-SNAPSHOT otherwise) plus tag and commit.
        assertThat(build.path("version").asString()).matches("\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+|0\\.0\\.1-SNAPSHOT");
        assertThat(build.path("artifact").asString()).isEqualTo("domain-api-app");
        assertThat(build.path("releaseTag").asString()).matches("v\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+|dev");
        assertThat(build.path("sourceRevision").asString()).matches("[a-f0-9]{40}|local");
        if (!"dev".equals(build.path("releaseTag").asString()))
            assertThat(build.path("releaseTag").asString()).isEqualTo("v" + build.path("version").asString());
        assertThat(response.body()).doesNotContain("password").doesNotContain("secret");
    }

    @Test void noOtherActuatorEndpointBecamePublic() throws Exception {
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/mappings", "/actuator/loggers",
                "/actuator/configprops", "/actuator/metrics", "/actuator/threaddump", "/actuator/heapdump", "/actuator/conditions"))
            assertThat(send("GET", path).statusCode()).as(path).isIn(401, 404);
        assertThat(send("POST", "/actuator/info").statusCode()).isNotEqualTo(200);
        assertThat(send("GET", "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test void applicationConfigurationExposesOnlyHealthAndInfo() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("management:\n  endpoints:\n    web:\n      exposure:\n        include: health,info\n");
        assertThat(Map.of("exposures", yaml.split("exposure:").length - 1)).containsEntry("exposures", 1);
    }
}
