package com.lookahead.learning.content.filter;

import com.lookahead.domain.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** DLV-969: a request body over its limit is one lookahead.limit line with the limit and the path; malformed JSON one refusal. */
class AccountRequestLimitsFilterLoggingTest {
    @Test void anOversizedBodyIsALimitLineAndMalformedJsonARefusal() throws Exception {
        var filter = new AccountRequestLimitsFilter();
        try (var log = LogCapture.start()) {
            var big = new MockHttpServletRequest("POST", "/api/v1/auth/register");
            big.setQueryString("email=person@example.invalid");
            big.setContent(("{\"name\":\"" + "x".repeat(9000) + "\"}").getBytes());
            var response = new MockHttpServletResponse();
            filter.doFilter(big, response, (request, reply) -> { throw new AssertionError("must not continue"); });
            assertThat(response.getStatus()).isEqualTo(413);
            var limit = log.only("request.body");
            assertThat(limit.getLoggerName()).isEqualTo("lookahead.limit");
            assertThat(limit.getFormattedMessage()).isEqualTo("Request body limit hit on /api/v1/auth/register: more than 8192 bytes; refused with 413");
            assertThat(LogCapture.keys(limit)).containsEntry("limit.name", "request-body-bytes").containsEntry("limit.max", 8192L)
                    .containsEntry("limit.window", "per request").containsEntry("url.path", "/api/v1/auth/register");

            var malformed = new MockHttpServletRequest("POST", "/api/v1/plans");
            malformed.setContent("{\"goal\":\"Private".getBytes());
            filter.doFilter(malformed, new MockHttpServletResponse(), (request, reply) -> { throw new AssertionError("must not continue"); });
            assertThat(LogCapture.keys(log.only("request.json"))).containsEntry("reason", "MALFORMED_JSON").containsEntry("url.path", "/api/v1/plans");
            log.containsNone("person@example.invalid", "Private", "xxxxxxxx");
        }
    }
}
