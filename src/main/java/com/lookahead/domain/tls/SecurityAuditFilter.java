package com.lookahead.domain.tls;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fixed event codes only: no request, identity, token, exception or payload fields. */
@Component @Profile("lower-cost-dev") @Order(-250)
public final class SecurityAuditFilter extends OncePerRequestFilter {
    enum Event { ADMISSION_GRANTED, ADMISSION_DENIED, ADMISSION_UNAVAILABLE, AUTHENTICATION_REJECTED, AUTHORIZATION_REJECTED, GATEWAY_IDENTITY_REJECTED, SESSION_ENDED }
    static void record(Event event) {
        LoggerFactory.getLogger("lookahead.security.audit").info("LOOKAHEAD_SECURITY_V1 event={} id={}",event.name(),UUID.randomUUID());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        chain.doFilter(request,response);
        if(response.getStatus()==401)record(Event.AUTHENTICATION_REJECTED);
        else if(response.getStatus()==403)record(Event.AUTHORIZATION_REJECTED);
    }
}
