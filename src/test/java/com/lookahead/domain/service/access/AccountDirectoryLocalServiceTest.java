package com.lookahead.domain.service.access;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.access.AccountDirectory;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.local.identity.IdentityAccountClient;
import com.lookahead.domain.local.identity.IdentityConfiguration;
import com.lookahead.domain.local.identity.IdentityConnection;
import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

/**
 * DLV-1016: the Local directory is a bean of its own; the first test is
 * AccountDirectoryServicePageTest's Local case, moved with the class it tests.
 */
class AccountDirectoryLocalServiceTest {
  @Test
  void localDirectoryUsesPrivateAuthenticationAndRejectsMalformedUpstreamPages() throws Exception {
    var body = new AtomicReference<String>("{\"accounts\":[],\"page\":2,\"hasNext\":false}");
    var status = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/accounts",
        exchange -> {
          assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("query=a%2Bb&page=2");
          assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var origin = "http://127.0.0.1:" + server.getAddress().getPort();
      var connection =
          new IdentityConnection(
              origin,
              origin,
              "gateway",
              "synthetic-verifier-secret-32-chars",
              Duration.ofSeconds(2),
              Duration.ofSeconds(2));
      var store = mock(AccountDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
      var directory =
          new AccountDirectoryLocalService(
              store,
              mock(StaffAccessService.class),
              new IdentityAccountClient(
                  connection, new IdentityConfiguration().identityRestClient(connection)));
      assertThat(directory.list("a+b", 2).accounts()).isEmpty();
      verifyNoInteractions(store);
      for (String malformed :
          new String[] {
            "{",
            "null",
            "{\"accounts\":null,\"page\":2}",
            "{\"accounts\":[],\"page\":1}",
            "{\"accounts\":[{}],\"page\":2}",
            "x".repeat(262145)
          }) {
        body.set(malformed);
        assertThatThrownBy(() -> directory.list("a+b", 2)).isInstanceOf(AccountFailure.class);
      }
      status.set(503);
      body.set("private upstream error");
      assertThatThrownBy(() -> directory.list("a+b", 2))
          .isInstanceOf(AccountFailure.class)
          .hasMessageNotContaining("private upstream error");
    } finally {
      server.stop(0);
    }
  }

  /**
   * The checks are the shared ones and come first; then Identity answers, and a looked-up account
   * gets its subject row.
   */
  @Test
  void lookupAndByIdCheckFirstThenAskIdentityAndALookedUpAccountGetsItsSubjectRow()
      throws Exception {
    var id = UUID.randomUUID();
    String account =
        "{\"accountId\":\"" + id + "\",\"username\":\"someone\",\"displayName\":\"Someone\"}";
    var seen = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/accounts",
        exchange -> {
          seen.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getPath()
                  + " "
                  + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = account.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var origin = "http://127.0.0.1:" + server.getAddress().getPort();
      var connection =
          new IdentityConnection(
              origin,
              origin,
              "gateway",
              "synthetic-verifier-secret-32-chars",
              Duration.ofSeconds(2),
              Duration.ofSeconds(2));
      var store = mock(AccountDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
      var staff = mock(StaffAccessService.class);
      AccountDirectoryService directory =
          new AccountDirectoryLocalService(
              store,
              staff,
              new IdentityAccountClient(
                  connection, new IdentityConfiguration().identityRestClient(connection)));
      var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);
      for (String query : new String[] {null, " ", "x".repeat(255), "a\nb"})
        assertThatThrownBy(() -> directory.lookup(owner, query))
            .isInstanceOfSatisfying(
                AccountFailure.class,
                f -> assertThat(f.code()).isEqualTo("INVALID_ACCOUNT_LOOKUP"));
      assertThatThrownBy(() -> directory.byId(null))
          .isInstanceOfSatisfying(
              AccountFailure.class, f -> assertThat(f.code()).isEqualTo("INVALID_ACCOUNT_LOOKUP"));
      assertThat(seen).isEmpty();
      verifyNoInteractions(store);
      assertThat(directory.lookup(owner, " Someone@Example.invalid "))
          .extracting(AccountDirectoryModels.Account::accountId)
          .isEqualTo(id);
      verify(staff, times(5)).requireOwner(owner);
      verify(store).ensureSubject(id);
      assertThat(directory.byId(id).username()).isEqualTo("someone");
      verifyNoMoreInteractions(store);
      assertThat(seen)
          .containsExactly(
              "POST /internal/v1/accounts/lookup {\"query\":\"someone@example.invalid\"}",
              "GET /internal/v1/accounts/" + id + " ");
    } finally {
      server.stop(0);
    }
  }
}
