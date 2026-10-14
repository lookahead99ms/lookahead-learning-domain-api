package com.lookahead.domain.tls;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.security.cert.X509Certificate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GatewayCertificateFilterTest {
 @Test void certificateRequiredEvenForHealthOnPublicListener()throws Exception{
  var req=new MockHttpServletRequest("GET","/actuator/health/readiness");req.setLocalPort(8443);var res=new MockHttpServletResponse();
  new GatewayCertificateFilter().doFilter(req,res,new MockFilterChain());assertEquals(403,res.getStatus());
 }
 @Test void onlyGatewayClientSanIsAuthorized()throws Exception{
  // X509Certificate is abstract; subclass mocking avoids instrumenting JDK
  // certificate internals alongside the coverage agent.
  var cert=mock(X509Certificate.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));when(cert.getBasicConstraints()).thenReturn(-1);when(cert.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
  when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2,"domain.lookahead-dev.internal")));assertFalse(GatewayCertificateFilter.authorized(cert));
  when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2,"gateway.lookahead-dev.internal")));assertTrue(GatewayCertificateFilter.authorized(cert));
  doThrow(new java.security.cert.CertificateExpiredException()).when(cert).checkValidity();assertFalse(GatewayCertificateFilter.authorized(cert));
 }
}
