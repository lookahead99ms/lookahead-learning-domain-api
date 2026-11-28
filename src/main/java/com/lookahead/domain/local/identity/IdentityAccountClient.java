package com.lookahead.domain.local.identity;

import com.lookahead.domain.client.AccountDirectoryClient;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.model.AccountDirectoryModels.Account;
import com.lookahead.domain.model.AccountDirectoryModels.Page;
import com.lookahead.domain.util.AccountDirectoryRules;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * The Local account directory calls to the Identity service: list accounts, look one up by email or
 * username, get one by id. Each call is authenticated with the verifier secret, bounded in size,
 * checked for shape and logged as one {@code lookahead.upstream} line. Exists only in Local mode.
 */
public class IdentityAccountClient implements AccountDirectoryClient {
  private final IdentityConnection identity;
  private final RestClient http;

  public IdentityAccountClient(IdentityConnection identity, RestClient http) {
    this.identity = identity;
    this.http = http;
  }

  /** {@code term} and {@code page} are already checked by the caller. */
  @Override
  public Page list(String term, int page) {
    long started = EventLog.started();
    int[] status = {0};
    try {
      var uri =
          UriComponentsBuilder.fromUriString(identity.upstream() + "/internal/v1/accounts")
              .queryParam("query", "{query}")
              .queryParam("page", page)
              .encode()
              .buildAndExpand(term)
              .toUri();
      return answered(
          "ListAccounts",
          started,
          http.get()
              .uri(uri)
              .headers(h -> h.setBasicAuth("lookahead-domain-verifier", identity.verifierSecret()))
              .exchange((sent, response) -> {
                status[0] = response.getStatusCode().value();
                if (response.getStatusCode().value() != 200)
                  throw AccountDirectoryRules.unavailable();
                byte[] bytes = response.getBody().readNBytes(262145);
                if (bytes.length > 262144) throw AccountDirectoryRules.unavailable();
                var result = new ObjectMapper().readValue(bytes, AccountDirectoryModels.Page.class);
                if (result == null
                    || result.accounts() == null
                    || result.accounts().size() > 50
                    || result.page() != page
                    || result.accounts().stream()
                        .anyMatch(a ->
                            a == null
                                || a.accountId() == null
                                || a.username() == null
                                || a.displayName() == null))
                  throw AccountDirectoryRules.unavailable();
                return result;
              }));
    } catch (AccountFailure expected) {
      throw refused("ListAccounts", started, status[0], expected);
    } catch (RuntimeException failure) {
      throw failed("ListAccounts", started, status[0], failure);
    }
  }

  private static <T> T answered(String operation, long started, T result) {
    EventLog.upstreamDone("Identity", operation, started);
    return result;
  }

  /**
   * Identity answered, but not with a usable account: not found is a refusal, anything else a
   * failure.
   */
  private static AccountFailure refused(
      String operation, long started, int status, AccountFailure outcome) {
    if ("ACCOUNT_NOT_FOUND".equals(outcome.code()))
      EventLog.upstreamRefused("Identity", operation, started, "ACCOUNT_NOT_FOUND");
    else
      EventLog.upstreamFailed(
          "Identity",
          operation,
          started,
          null,
          status == 0 ? null : status,
          status == 200 ? "INVALID_RESPONSE" : null);
    return outcome;
  }

  private static AccountFailure failed(
      String operation, long started, int status, RuntimeException failure) {
    EventLog.upstreamFailed(
        "Identity",
        operation,
        started,
        failure,
        status == 0 ? EventLog.status(failure) : Integer.valueOf(status),
        null);
    return AccountDirectoryRules.unavailable();
  }

  /**
   * {@code found} runs for a usable answer before the call is logged as done, as it did inside the
   * directory service.
   */
  @Override
  public Account lookup(String query, Consumer<Account> found) {
    long started = EventLog.started();
    int[] status = {0};
    try {
      return answered(
          "LookupAccount",
          started,
          http.post()
              .uri(identity.upstream() + "/internal/v1/accounts/lookup")
              .headers(h -> h.setBasicAuth("lookahead-domain-verifier", identity.verifierSecret()))
              .body(Map.of("query", query))
              .exchange((sent, response) -> {
                status[0] = response.getStatusCode().value();
                if (response.getStatusCode().value() == 404) throw AccountDirectoryRules.missing();
                if (response.getStatusCode().value() != 200)
                  throw AccountDirectoryRules.unavailable();
                byte[] bytes = response.getBody().readNBytes(4097);
                if (bytes.length > 4096) throw AccountDirectoryRules.unavailable();
                var account =
                    new ObjectMapper().readValue(bytes, AccountDirectoryModels.Account.class);
                if (account.accountId() == null
                    || account.username() == null
                    || account.displayName() == null) throw AccountDirectoryRules.unavailable();
                found.accept(account);
                return account;
              }));
    } catch (AccountFailure expected) {
      throw refused("LookupAccount", started, status[0], expected);
    } catch (RuntimeException failure) {
      throw failed("LookupAccount", started, status[0], failure);
    }
  }

  @Override
  public Account byId(UUID id) {
    long started = EventLog.started();
    int[] status = {0};
    try {
      return answered(
          "GetAccount",
          started,
          http.get()
              .uri(identity.upstream() + "/internal/v1/accounts/" + id)
              .headers(h -> h.setBasicAuth("lookahead-domain-verifier", identity.verifierSecret()))
              .exchange((sent, response) -> {
                status[0] = response.getStatusCode().value();
                if (response.getStatusCode().value() == 404) throw AccountDirectoryRules.missing();
                if (response.getStatusCode().value() != 200)
                  throw AccountDirectoryRules.unavailable();
                byte[] bytes = response.getBody().readNBytes(4097);
                if (bytes.length > 4096) throw AccountDirectoryRules.unavailable();
                var account =
                    new ObjectMapper().readValue(bytes, AccountDirectoryModels.Account.class);
                if (account == null || !id.equals(account.accountId()))
                  throw AccountDirectoryRules.unavailable();
                return account;
              }));
    } catch (AccountFailure expected) {
      throw refused("GetAccount", started, status[0], expected);
    } catch (RuntimeException failure) {
      throw failed("GetAccount", started, status[0], failure);
    }
  }
}
