package com.lookahead.domain.tls;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
class SecurityAuditFilterTest {
    @Test void deniesAreAuditedWithoutRequestSecrets()throws Exception {
        var logger=(Logger)LoggerFactory.getLogger("lookahead.security.audit");
        var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            for(int status:new int[]{200,401,403}) {
                var request=new MockHttpServletRequest("GET","/private/secret-path");request.addHeader("Authorization","Bearer private-token");
                new SecurityAuditFilter().doFilter(request,new MockHttpServletResponse(),(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(status));
            }
            assertEquals(2,capture.list.size());
            assertTrue(capture.list.getFirst().getFormattedMessage().contains("AUTHENTICATION_REJECTED"));
            assertTrue(capture.list.getLast().getFormattedMessage().contains("AUTHORIZATION_REJECTED"));
            for(var event:capture.list)assertTrue(event.getFormattedMessage().matches("LOOKAHEAD_SECURITY_V1 event=[A-Z_]+ id=[a-f0-9-]{36}"));
        } finally {logger.detachAppender(capture);capture.stop();}
    }
}
