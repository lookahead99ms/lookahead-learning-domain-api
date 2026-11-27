package com.lookahead.domain.security.filter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
/** The security audit event of the DEV mTLS profile ({@code lower-cost-dev}), written by the request filter (DLV-1016: it was a filter of its own). */
class RequestLogFilterSecurityAuditTest {
    private static MockEnvironment profile(String... profiles){var environment=new MockEnvironment();environment.setActiveProfiles(profiles);return environment;}
    @Test void deniesAreAuditedWithoutRequestSecrets()throws Exception {
        var logger=(Logger)LoggerFactory.getLogger("lookahead.security.audit");
        var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            for(int status:new int[]{200,401,403}) {
                var request=new MockHttpServletRequest("GET","/private/secret-path");request.addHeader("Authorization","Bearer private-token");
                new RequestLogFilter(profile("lower-cost-dev")).doFilter(request,new MockHttpServletResponse(),(req,res)->((HttpServletResponse)res).setStatus(status));
            }
            assertEquals(2,capture.list.size());
            assertTrue(capture.list.getFirst().getFormattedMessage().contains("AUTHENTICATION_REJECTED"));
            assertTrue(capture.list.getLast().getFormattedMessage().contains("AUTHORIZATION_REJECTED"));
            for(var event:capture.list)assertTrue(event.getFormattedMessage().matches("LOOKAHEAD_SECURITY_V1 event=[A-Z_]+ id=[a-f0-9-]{36}"));
        } finally {logger.detachAppender(capture);capture.stop();}
    }
    /** As the filter of its own did: the event comes before the request's summary line, carries the request ID, and is not written for a request that ended in an exception. */
    @Test void theEventComesBeforeTheSummaryLineAndNeverForARequestThatEndedInAnException()throws Exception {
        var audit=(Logger)LoggerFactory.getLogger("lookahead.security.audit");var http=(Logger)LoggerFactory.getLogger("lookahead.http");var failure=(Logger)LoggerFactory.getLogger("lookahead.failure");
        var capture=new ListAppender<ILoggingEvent>();capture.start();audit.addAppender(capture);http.addAppender(capture);failure.addAppender(capture);
        try {
            var filter=new RequestLogFilter(profile("lower-cost-dev"));
            var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET","/api/v1/plans"),response,(req,res)->((HttpServletResponse)res).setStatus(403));
            assertEquals(List.of("lookahead.security.audit","lookahead.http"),capture.list.stream().map(ILoggingEvent::getLoggerName).toList());
            assertEquals(response.getHeader("X-Request-Id"),capture.list.getFirst().getMDCPropertyMap().get("requestId"));
            capture.list.clear();
            filter.doFilter(new MockHttpServletRequest("GET","/api/v1/plans"),new MockHttpServletResponse(),(req,res)->{((HttpServletResponse)res).setStatus(403);throw new IllegalStateException("after the status was set");});
            assertEquals(List.of("lookahead.failure","lookahead.http"),capture.list.stream().map(ILoggingEvent::getLoggerName).toList());
        } finally {audit.detachAppender(capture);http.detachAppender(capture);failure.detachAppender(capture);capture.stop();}
    }
    /** The filter of its own existed only with the profile: without it no request writes the event. */
    @Test void withoutTheProfileNoEventIsWritten()throws Exception {
        var logger=(Logger)LoggerFactory.getLogger("lookahead.security.audit");
        var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            for(MockEnvironment environment:new MockEnvironment[]{profile(),profile("accounts","resource"),profile("local","local-test")})
                for(int status:new int[]{200,401,403})
                    new RequestLogFilter(environment).doFilter(new MockHttpServletRequest("GET","/private/secret-path"),new MockHttpServletResponse(),(req,res)->((HttpServletResponse)res).setStatus(status));
            assertTrue(capture.list.isEmpty());
        } finally {logger.detachAppender(capture);capture.stop();}
    }
}
