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
        String reason = value instanceof X509Certificate[] certificates && certificates.length > 0 ? reason(certificates[0]) : "CERTIFICATE_MISSING";
        if (reason != null) {
            SecurityAuditFilter.record(SecurityAuditFilter.Event.GATEWAY_IDENTITY_REJECTED);
            rejected(request, reason);
            response.setStatus(403); response.setHeader("Cache-Control", "no-store"); return;
        }
        chain.doFilter(request, response);
    }
    static boolean authorized(X509Certificate certificate) { return reason(certificate) == null; }

    /** Why the peer certificate is not the Gateway's, as a stable code; null when it is. */
    static String reason(X509Certificate certificate) {
        try {
            certificate.checkValidity();
            if (certificate.getBasicConstraints() >= 0) return "CERTIFICATE_IS_CA";
            if (certificate.getExtendedKeyUsage() == null || !certificate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")) return "CERTIFICATE_NOT_CLIENT_AUTH";
            var sans = certificate.getSubjectAlternativeNames();
            return sans != null && sans.stream().anyMatch(s -> Integer.valueOf(2).equals(s.get(0)) && "gateway.lookahead-dev.internal".equals(s.get(1)))
                    ? null : "CERTIFICATE_NOT_GATEWAY";
        } catch (java.security.cert.CertificateExpiredException expired) { return "CERTIFICATE_EXPIRED"; }
        catch (java.security.cert.CertificateNotYetValidException early) { return "CERTIFICATE_NOT_YET_VALID"; }
        catch (Exception invalid) { return "CERTIFICATE_UNREADABLE"; }
    }

    /**
     * DLV-969 (owner, 2026-11-04): the audit event says only that the Gateway identity was rejected; this line says why
     * (for example an expired Gateway client certificate), once per reason a minute. Never the certificate's names.
     */
    private static final com.lookahead.domain.Throttle REJECTED = new com.lookahead.domain.Throttle();
    private static void rejected(HttpServletRequest request, String reason) {
        if (REJECTED.due(reason)) com.lookahead.domain.Events.refused(com.lookahead.domain.Events.EVENT, "gateway.certificate", reason)
                .addKeyValue("url.path", com.lookahead.domain.Events.path(request)).addKeyValue("http.status", 403)
                .log("Request on {} refused with 403: the TLS client certificate is not the Gateway's ({})", com.lookahead.domain.Events.path(request), reason);
    }
}
