package com.lookahead.domain.log;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** DLV-969 (owner, 2026-11-04): the reference an error answer carries is the request ID while a request is served. */
class RequestLogTest {
    private static final String GATEWAY_ID = "6f1c2b9e-4d3a-4e8b-9c7d-0a1b2c3d4e5f";

    @AfterEach
    void release() {
        MDC.clear();
    }

    @Test
    void outsideARequestTheReferenceIsAFreshUuidAndInsideItIsTheRequestId() {
        String first = RequestLog.reference();
        assertThat(UUID.fromString(first).toString()).isEqualTo(first);
        assertThat(RequestLog.reference()).isNotEqualTo(first);

        MDC.put("requestId", GATEWAY_ID);
        assertThat(RequestLog.reference()).isEqualTo(GATEWAY_ID);
    }
}
