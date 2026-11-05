package com.lookahead.learning.content.handler;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.testaccounts.CreatedTestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** DLV-969 (2026-11-04): a body that does not fit its record logs which field failed, by name only, never its value. */
class MalformedJsonLoggingTest {
    private static HttpMessageNotReadableException unreadable(String body) {
        Throwable cause = catchThrowable(() -> JsonMapper.builder().build().readValue(body, CreatedTestAccounts.Create.class));
        return new HttpMessageNotReadableException("unreadable", cause, new MockHttpInputMessage(body.getBytes()));
    }

    @Test void namesTheFieldThatCouldNotBeReadAndNeverItsValue() {
        try (var log = LogCapture.start()) {
            var answer = new AccountErrorHandler().malformed(unreadable("{\"roster\":\"secret-value\"}"),
                    new MockHttpServletRequest("POST", "/api/v1/admin/test-accounts"));
            assertThat(answer.getStatusCode().value()).isEqualTo(400);
            var line = log.only("request.json");
            assertThat(line.getFormattedMessage()).startsWith("Request body on POST /api/v1/admin/test-accounts refused with 400: field roster could not be read (");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "MALFORMED_JSON").containsEntry("json.field", "roster");
            log.containsNone("secret-value");
        }
    }

    @Test void nestedFieldsAreDottedAndABrokenBodyIsTheBody() {
        assertThat(AccountErrorHandler.fieldOf(unreadable("{\"purchaseDecision\":{\"purchaseId\":\"not-a-uuid\"}}"))).isEqualTo("purchaseDecision.purchaseId");
        assertThat(AccountErrorHandler.fieldOf(new HttpMessageNotReadableException("broken", new MockHttpInputMessage(new byte[0])))).isEmpty();
        try (var log = LogCapture.start()) {
            new AccountErrorHandler().malformed(new HttpMessageNotReadableException("broken", new MockHttpInputMessage(new byte[0])),
                    new MockHttpServletRequest("POST", "/api/v1/plans"));
            assertThat(log.only("request.json").getFormattedMessage()).contains("the body could not be read");
        }
    }
}
