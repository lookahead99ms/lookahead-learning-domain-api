package com.lookahead.platform.security;

import java.net.http.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Uncached authoritative token checks over a fixed, authenticated, bounded internal connection. */
public class IdentityVerificationClient {
    public record Verification(boolean active, String subject, String username, String displayName, String clientId) {}
    private final IdentitySettings settings;
    private final RestClient http;
    private final ObjectMapper mapper;

    public IdentityVerificationClient(IdentitySettings settings, ObjectMapper mapper) {
        this.settings = settings; this.mapper = mapper;
        var client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public Verification verify(String accessToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", accessToken);
        try {
            return http.post().uri(settings.upstream() + "/internal/v1/tokens/verify")
                    .headers(headers -> headers.setBasicAuth("lookahead-platform-verifier", settings.verifierSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) throw new IdentityUnavailableException();
                        byte[] body = response.getBody().readNBytes(16 * 1024 + 1);
                        if (body.length > 16 * 1024) throw new IdentityUnavailableException();
                        var node = mapper.readTree(body);
                        if (node == null || !node.isObject() || !node.path("active").isBoolean()) throw new IdentityUnavailableException();
                        if (!node.path("active").asBoolean()) return new Verification(false, null, null, null, null);
                        String subject = text(node, "subject", 36), username = text(node, "username", 254);
                        String name = text(node, "displayName", 160), clientId = text(node, "clientId", 80);
                        return new Verification(true, subject, username, name, clientId);
                    });
        } catch (Exception error) {
            // Never retain the HTTP exception: it may include an upstream body or submitted credentials.
            throw new IdentityUnavailableException();
        }
    }

    private static String text(tools.jackson.databind.JsonNode node, String name, int limit) {
        var value = node.path(name);
        if (!value.isString() || value.asText().isBlank() || value.asText().length() > limit
                || value.asText().codePoints().anyMatch(Character::isISOControl)) throw new IdentityUnavailableException();
        return value.asText();
    }
}
