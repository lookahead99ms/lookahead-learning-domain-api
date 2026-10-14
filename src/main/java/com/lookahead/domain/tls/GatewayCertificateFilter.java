package com.lookahead.domain.tls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** TLS authenticates the chain; this filter authorizes the specific Gateway identity. */
@Component @Profile("lower-cost-dev") @Order(-200)
public final class GatewayCertificateFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getLocalPort() == 8081 && java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr())) {
            chain.doFilter(request, response); return;
        }
        Object value = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(value instanceof X509Certificate[] certificates) || certificates.length == 0 || !authorized(certificates[0])) {
            SecurityAuditFilter.record(SecurityAuditFilter.Event.GATEWAY_IDENTITY_REJECTED);
            response.setStatus(403); response.setHeader("Cache-Control", "no-store"); return;
        }
        chain.doFilter(request, response);
    }
    static boolean authorized(X509Certificate certificate) {
        try {
            certificate.checkValidity();
            var sans = certificate.getSubjectAlternativeNames();
            return certificate.getBasicConstraints() < 0 && certificate.getExtendedKeyUsage() != null
                && certificate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2") && sans != null
                && sans.stream().anyMatch(s -> Integer.valueOf(2).equals(s.get(0)) && "gateway.lookahead-dev.internal".equals(s.get(1)));
        } catch (Exception invalid) { return false; }
    }
}
