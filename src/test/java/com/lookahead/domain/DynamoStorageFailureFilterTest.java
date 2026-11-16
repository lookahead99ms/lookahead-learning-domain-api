package com.lookahead.domain;

import com.lookahead.learning.content.handler.DynamoStorageFailureMappingTest;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import static org.assertj.core.api.Assertions.*;

/** DLV-970: a DynamoDB failure before MVC (bearer authentication) is the same 503, a conflict the same 409, and the SDK message stays out. */
class DynamoStorageFailureFilterTest {
    final DomainStorageFailureFilter filter = new DomainStorageFailureFilter();

    MockHttpServletResponse run(RuntimeException failure) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/account/session");
        var response = new MockHttpServletResponse();
        FilterChain chain = (q, s) -> { throw failure; };
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test void theStoresOutageAndTheSdksFailuresAnswer503WithOneWarnLine() throws Exception {
        for (RuntimeException failure : new RuntimeException[] {DynamoStorageFailureMappingTest.unavailable(), DynamoStorageFailureMappingTest.readUnavailable(),
                SdkClientException.builder().message(DynamoStorageFailureMappingTest.SECRET).build(), DynamoDbException.builder().message(DynamoStorageFailureMappingTest.SECRET).build()}) {
            try (var log = LogCapture.start()) {
                var response = run(failure);
                assertThat(response.getStatus()).as(failure.getClass().getSimpleName()).isEqualTo(503);
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(response.getContentType()).isEqualTo("application/json");
                assertThat(response.getContentAsString()).isEqualTo("{\"status\":503,\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\",\"message\":\"Account storage is unavailable; retain your work and retry\"}");
                var line = log.only("request.dependency");
                assertThat(LogCapture.keys(line)).containsEntry("reason", "ACCOUNT_STORAGE_UNAVAILABLE").containsEntry("http.status", 503).containsEntry("url.path", "/api/v1/account/session");
                assertThat(line.getFormattedMessage()).startsWith("Account storage unavailable on /api/v1/account/session: ACCOUNT_STORAGE_UNAVAILABLE (").doesNotContain("4400", "do-not-expose");
                log.containsNone("4400", "do-not-expose");
            }
        }
    }

    @Test void aConflictBeforeMvcIsTheRevisionConflictAnswer() throws Exception {
        try (var log = LogCapture.start()) {
            var response = run(DynamoStorageFailureMappingTest.conflict());
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getContentAsString()).isEqualTo("{\"status\":409,\"code\":\"REVISION_CONFLICT\",\"message\":\"The record changed while you were saving; reload before retrying\"}");
            assertThat(LogCapture.keys(log.only("request.dependency"))).containsEntry("reason", "REVISION_CONFLICT").containsEntry("http.status", 409);
            log.containsNone("do-not-expose");
        }
    }

    @Test void aCommittedResponseIsNotRewrittenAndOtherFailuresPassThrough() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/account/session");
        var response = new MockHttpServletResponse();
        response.getWriter().write("partial"); response.flushBuffer();
        var failure = DynamoStorageFailureMappingTest.unavailable();
        assertThatThrownBy(() -> filter.doFilter(request, response, (q, s) -> { throw failure; })).isSameAs(failure);
        assertThatThrownBy(() -> run(new IllegalStateException("synthetic"))).isInstanceOf(IllegalStateException.class);
    }
}
