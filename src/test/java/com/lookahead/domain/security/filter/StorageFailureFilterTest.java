package com.lookahead.domain.security.filter;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.handler.AccountErrorHandlerStorageFailureTest;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

/**
 * DLV-970: a DynamoDB failure before MVC (bearer authentication) is the same 503, a conflict the
 * same 409; the ERROR line quotes the SDK (plain logs, 2026-11-17).
 */
class StorageFailureFilterTest {
  final StorageFailureFilter filter = new StorageFailureFilter();

  MockHttpServletResponse run(RuntimeException failure) throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/account/session");
    var response = new MockHttpServletResponse();
    FilterChain chain =
        (q, s) -> {
          throw failure;
        };
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void theStoresOutageAndTheSdksFailuresAnswer503WithOneErrorLine() throws Exception {
    for (RuntimeException failure :
        new RuntimeException[] {
          AccountErrorHandlerStorageFailureTest.unavailable(),
          AccountErrorHandlerStorageFailureTest.readUnavailable(),
          SdkClientException.builder()
              .message(AccountErrorHandlerStorageFailureTest.SECRET)
              .build(),
          DynamoDbException.builder().message(AccountErrorHandlerStorageFailureTest.SECRET).build()
        }) {
      try (var log = LogCapture.start()) {
        var response = run(failure);
        assertThat(response.getStatus()).as(failure.getClass().getSimpleName()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
            .isEqualTo(
                "{\"status\":503,\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\",\"message\":\"Account"
                    + " storage is unavailable; retain your work and retry\"}");
        var line = log.only("request.dependency");
        assertThat(LogCapture.keys(line))
            .containsEntry("reason", "ACCOUNT_STORAGE_UNAVAILABLE")
            .containsEntry("http.status", 503)
            .containsEntry("url.path", "/api/v1/account/session");
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getFormattedMessage())
            .startsWith(
                "Account storage unavailable on /api/v1/account/session:"
                    + " ACCOUNT_STORAGE_UNAVAILABLE (")
            .contains(
                AccountErrorHandlerStorageFailureTest.SECRET + "); answered 503 until it recovers");
        assertThat(response.getContentAsString()).doesNotContain("4400", "do-not-expose");
      }
    }
  }

  @Test
  void aConflictBeforeMvcIsTheRevisionConflictAnswer() throws Exception {
    try (var log = LogCapture.start()) {
      var response = run(AccountErrorHandlerStorageFailureTest.conflict());
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(response.getContentAsString())
          .isEqualTo(
              "{\"status\":409,\"code\":\"REVISION_CONFLICT\",\"message\":\"The record changed"
                  + " while you were saving; reload before retrying\"}");
      assertThat(LogCapture.keys(log.only("request.dependency")))
          .containsEntry("reason", "REVISION_CONFLICT")
          .containsEntry("http.status", 409);
      log.containsNone("do-not-expose");
    }
  }

  @Test
  void aCommittedResponseIsNotRewrittenAndOtherFailuresPassThrough() throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/account/session");
    var response = new MockHttpServletResponse();
    response.getWriter().write("partial");
    response.flushBuffer();
    var failure = AccountErrorHandlerStorageFailureTest.unavailable();
    assertThatThrownBy(() ->
            filter.doFilter(
                request,
                response,
                (q, s) -> {
                  throw failure;
                }))
        .isSameAs(failure);
    assertThatThrownBy(() -> run(new IllegalStateException("synthetic")))
        .isInstanceOf(IllegalStateException.class);
  }
}
