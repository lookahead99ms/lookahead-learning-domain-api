package com.lookahead.domain.security.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.log.LogCapture;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.mock.web.*;

class BffCertificateFilterTest {
  @Test
  void certificateRequiredEvenForHealthOnPublicListener() throws Exception {
    var req = new MockHttpServletRequest("GET", "/actuator/health/readiness");
    req.setLocalPort(8443);
    var res = new MockHttpServletResponse();
    new BffCertificateFilter().doFilter(req, res, new MockFilterChain());
    assertEquals(403, res.getStatus());
  }

  @Test
  void onlyGatewayClientSanIsAuthorized() throws Exception {
    // X509Certificate is abstract; subclass mocking avoids instrumenting JDK
    // certificate internals alongside the coverage agent.
    var cert = mock(X509Certificate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    when(cert.getBasicConstraints()).thenReturn(-1);
    when(cert.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
    when(cert.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(2, "domain.lookahead-dev.internal")));
    assertNotNull(BffCertificateFilter.reason(cert));
    when(cert.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(2, "gateway.lookahead-dev.internal")));
    assertNull(BffCertificateFilter.reason(cert));
    doThrow(new CertificateExpiredException()).when(cert).checkValidity();
    assertNotNull(BffCertificateFilter.reason(cert));
  }

  /**
   * DLV-969 (owner, 2026-11-04): a refused Gateway certificate says why in the log, once per reason
   * a minute.
   */
  @Test
  void aRefusedGatewayCertificateNamesItsReason() throws Exception {
    var cert = mock(X509Certificate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    when(cert.getBasicConstraints()).thenReturn(-1);
    when(cert.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
    when(cert.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(2, "intruder.example.internal")));
    assertEquals("CERTIFICATE_NOT_GATEWAY", BffCertificateFilter.reason(cert));
    when(cert.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.1"));
    assertEquals("CERTIFICATE_NOT_CLIENT_AUTH", BffCertificateFilter.reason(cert));
    when(cert.getBasicConstraints()).thenReturn(0);
    assertEquals("CERTIFICATE_IS_CA", BffCertificateFilter.reason(cert));
    doThrow(new CertificateNotYetValidException()).when(cert).checkValidity();
    assertEquals("CERTIFICATE_NOT_YET_VALID", BffCertificateFilter.reason(cert));
    doThrow(new IllegalStateException("unreadable")).when(cert).checkValidity();
    assertEquals("CERTIFICATE_UNREADABLE", BffCertificateFilter.reason(cert));
    doThrow(new CertificateExpiredException()).when(cert).checkValidity();
    try (var log = LogCapture.start()) {
      for (int i = 0; i < 3; i++) {
        var req = new MockHttpServletRequest("GET", "/api/v1/plans");
        req.setLocalPort(8443);
        req.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {cert});
        var res = new MockHttpServletResponse();
        new BffCertificateFilter().doFilter(req, res, new MockFilterChain());
        assertEquals(403, res.getStatus());
      }
      var req = new MockHttpServletRequest("GET", "/api/v1/plans");
      req.setLocalPort(8443);
      new BffCertificateFilter()
          .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
      assertEquals(
          List.of(
              "Request on /api/v1/plans refused with 403: the TLS client certificate is not the"
                  + " Gateway's (CERTIFICATE_EXPIRED)",
              "Request on /api/v1/plans refused with 403: the TLS client certificate is not the"
                  + " Gateway's (CERTIFICATE_MISSING)"),
          log.messages());
      assertEquals(Level.WARN, log.lines().getFirst().getLevel());
      log.containsNone("intruder");
    }
  }
}
