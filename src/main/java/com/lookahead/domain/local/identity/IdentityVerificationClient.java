package com.lookahead.domain.local.identity;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.log.EventLog;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Uncached authoritative token checks over a fixed, authenticated, bounded internal connection. */
public class IdentityVerificationClient {
  public record Verification(
      boolean active, String subject, String username, String displayName, String clientId) {}

  private final IdentityConnection connection;
  private final RestClient http;
  private final ObjectMapper mapper;

  public IdentityVerificationClient(
      IdentityConnection connection, RestClient http, ObjectMapper mapper) {
    this.connection = connection;
    this.mapper = mapper;
    this.http = http;
  }

  public Verification verify(String accessToken) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("token", accessToken);
    long started = EventLog.started();
    int[] status = {0};
    try {
      var verification =
          http.post()
              .uri(connection.upstream() + "/internal/v1/tokens/verify")
              .headers(headers ->
                  headers.setBasicAuth("lookahead-domain-verifier", connection.verifierSecret()))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .exchange((request, response) -> {
                status[0] = response.getStatusCode().value();
                if (response.getStatusCode().value() != 200)
                  throw new SignInCheckUnavailableException();
                byte[] body = response.getBody().readNBytes(16 * 1024 + 1);
                if (body.length > 16 * 1024) throw new SignInCheckUnavailableException();
                var node = mapper.readTree(body);
                if (node == null || !node.isObject() || !node.path("active").isBoolean())
                  throw new SignInCheckUnavailableException();
                if (!node.path("active").asBoolean())
                  return new Verification(false, null, null, null, null);
                String subject = text(node, "subject", 36), username = text(node, "username", 254);
                String name = text(node, "displayName", 160), clientId = text(node, "clientId", 80);
                return new Verification(true, subject, username, name, clientId);
              });
      if (verification.active()) EventLog.upstreamDone("Identity", "VerifyToken", started);
      else EventLog.upstreamRefused("Identity", "VerifyToken", started, "TOKEN_INACTIVE");
      return verification;
    } catch (Exception error) {
      // Never retain the HTTP exception: it may include an upstream body or submitted credentials.
      // Its type is logged.
      Integer answered = status[0] == 0 ? EventLog.status(error) : Integer.valueOf(status[0]);
      EventLog.upstreamFailed(
          "Identity",
          "VerifyToken",
          started,
          error instanceof SignInCheckUnavailableException ? null : error,
          answered,
          error instanceof SignInCheckUnavailableException && Integer.valueOf(200).equals(answered)
              ? "INVALID_RESPONSE"
              : null);
      throw new SignInCheckUnavailableException();
    }
  }

  private static String text(JsonNode node, String name, int limit) {
    var value = node.path(name);
    if (!value.isString()
        || value.asText().isBlank()
        || value.asText().length() > limit
        || value.asText().codePoints().anyMatch(Character::isISOControl))
      throw new SignInCheckUnavailableException();
    return value.asText();
  }
}
