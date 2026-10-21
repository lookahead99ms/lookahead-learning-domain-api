package com.lookahead.domain.testaccounts;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=TestAccountHttpTest.App.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.name=test-account-http", "server.address=127.0.0.1", "app.deployment-environment=local",
                "app.test-accounts.enabled=true", "app.test-accounts.operator-secret=synthetic-test-account-operator-credential-only"})
@ActiveProfiles({"accounts", "local-test"})
class TestAccountHttpTest {
    @Configuration
    @EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @ComponentScan(basePackageClasses=TestAccountSecurity.class, useDefaultFilters=false,
            includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=TestAccountSecurity.class))
    @Import({TestAccountPolicy.class, TestAccountsController.class})
    static class App {
        @Bean TestAccountsService service() {
            var service=mock(TestAccountsService.class);
            when(service.seed()).thenReturn(35);
            return service;
        }
        @Bean CreatedTestAccounts created() { return mock(CreatedTestAccounts.class); }
    }
    @LocalServerPort int port;
    private HttpResponse<String> send(String method, String path, String credentials) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build();
        String token = null;
        if ("POST".equals(method)) {
            var csrfRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/test-accounts/csrf"))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(OPERATOR.getBytes(StandardCharsets.UTF_8))).GET().build();
            var csrfResponse = client.send(csrfRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(csrfResponse.statusCode()).isEqualTo(200);
            assertThat(csrfResponse.headers().firstValue("cache-control")).contains("no-store");
            token = new tools.jackson.databind.ObjectMapper().readTree(csrfResponse.body()).get("token").asString();
        }
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/test-accounts/"+path))
                .header("Content-Type", "application/json").header("Cookie", "JSESSIONID=forged");
        if(credentials!=null)builder.header("Authorization", "Basic "+Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        if (token != null) builder.header("X-XSRF-TOKEN", token);
        builder.method(method,HttpRequest.BodyPublishers.ofString(
                "{\"learnerPassword\":\"synthetic-learner-only\",\"staffPassword\":\"synthetic-staff-only\"}"));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static final String OPERATOR="lookahead-test-account-operator:synthetic-test-account-operator-credential-only";
    @Test void onlyDedicatedCredentialCanSeedOrRotate() throws Exception {
        for(String path:List.of("seed")) {
            for(String credentials:new String[]{null,"lookahead-test-account-operator:wrong",
                    "lookahead-domain-verifier:synthetic-test-account-operator-credential-only"}) {
                var response=send("POST",path,credentials);
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.headers().firstValue("location")).isEmpty();
            }
            var success=send("POST",path,OPERATOR);
            assertThat(success.statusCode()).isEqualTo(200);
            assertThat(success.headers().firstValue("cache-control")).contains("no-store");
            assertThat(success.headers().allValues("set-cookie")).allMatch(value -> value.startsWith("XSRF-TOKEN="));
            assertThat(success.body()).doesNotContain("synthetic-learner", "synthetic-staff");
        }
        assertThat(send("POST","seed",OPERATOR).body()).contains("\"created\":35");
    }
    @org.springframework.beans.factory.annotation.Autowired TestAccountsService service;
    @Test void browserAndSimpleRequestsCannotSeedEvenWithOperatorCredentials() throws Exception {
        clearInvocations(service);
        for (String type : List.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data", "")) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/test-accounts/seed"))
                    .header("Authorization", "Basic "+Base64.getEncoder().encodeToString(OPERATOR.getBytes(StandardCharsets.UTF_8)));
            if (!type.isEmpty()) request.header("Content-Type", type);
            var response=HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
        }
        for (String header : List.of("Origin", "Sec-Fetch-Site")) {
            for (String method : List.of("POST", "OPTIONS")) {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/test-accounts/seed"))
                        .header("Authorization", "Basic "+Base64.getEncoder().encodeToString(OPERATOR.getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/json")
                        .header(header, header.equals("Origin") ? "https://attacker.invalid" : "cross-site")
                        .method(method,HttpRequest.BodyPublishers.ofString("{}"));
                var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("access-control-allow-origin")).isEmpty();
            }
        }
        verifyNoInteractions(service);
    }
    @Test void basicCredentialAloneCannotMutate() throws Exception {
        clearInvocations(service);
        for (String token : List.of("", "wrong")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/test-accounts/seed"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(OPERATOR.getBytes(StandardCharsets.UTF_8)))
                    .header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertThat(HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        verifyNoInteractions(service);
        assertThat(send("GET", "csrf", null).statusCode()).isEqualTo(401);
    }
    @Test void safeMethodsAndUnlistedOperationsCannotMutate() throws Exception {
        assertThat(send("GET","seed",OPERATOR).statusCode()).isEqualTo(403);
        assertThat(send("POST","reset",OPERATOR).statusCode()).isEqualTo(403);
        assertThat(send("POST","passwords",OPERATOR).statusCode()).isEqualTo(403);
    }
}
