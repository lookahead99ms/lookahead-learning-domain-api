package com.lookahead.domain.security.filter;

import com.lookahead.domain.log.LogCapture;
import java.util.Arrays;
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

    /** DLV-1016: the same 413 and line on the paths the filter serves in a running application, each with its own limit. */
    @Test void everyServedPathOverItsOwnLimitIsTheSame413AndLine() throws Exception {
        var filter = new AccountRequestLimitsFilter();
        Object[][] served = {
                {"/api/v1/author/review-artifacts/learn-docker/events", 16 * 1024},
                {"/api/v1/plans/11111111-2222-3333-4444-555555555555/activity", 256 * 1024},
                {"/api/v1/plans", 8 * 1024 * 1024},
                {"/api/v1/support", 8 * 1024 * 1024}};
        for (Object[] row : served) {
            String path = (String) row[0];
            int max = (Integer) row[1];
            try (var log = LogCapture.start()) {
                var big = new MockHttpServletRequest("POST", path);
                byte[] body = new byte[max + 1];
                Arrays.fill(body, (byte) ' ');
                big.setContent(body);
                var response = new MockHttpServletResponse();
                filter.doFilter(big, response, (request, reply) -> { throw new AssertionError("must not continue"); });
                assertThat(response.getStatus()).as(path).isEqualTo(413);
                assertThat(response.getContentAsString()).isEqualTo("{\"status\":413,\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request exceeds JSON limits or is malformed\"}");
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                var limit = log.only("request.body");
                assertThat(limit.getLoggerName()).isEqualTo("lookahead.limit");
                assertThat(limit.getFormattedMessage()).isEqualTo("Request body limit hit on " + path + ": more than " + max + " bytes; refused with 413");
                assertThat(LogCapture.keys(limit)).containsEntry("limit.name", "request-body-bytes").containsEntry("limit.max", (long) max)
                        .containsEntry("limit.window", "per request").containsEntry("url.path", path).containsEntry("http.status", 413);
            }
            // A body of exactly the limit passes on (blank JSON text: the scan reads no token and refuses nothing).
            var fits = new MockHttpServletRequest("POST", path);
            byte[] body = new byte[max];
            Arrays.fill(body, (byte) ' ');
            fits.setContent(body);
            boolean[] continued = {false};
            filter.doFilter(fits, new MockHttpServletResponse(), (request, reply) -> continued[0] = true);
            assertThat(continued[0]).as(path).isTrue();
        }
    }
}
