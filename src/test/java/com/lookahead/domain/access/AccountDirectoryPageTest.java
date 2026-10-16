package com.lookahead.domain.access;
import com.lookahead.domain.security.IdentitySettings;
import com.lookahead.learning.content.exception.AccountFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AccountDirectoryPageTest {
 @Test void rejectsInvalidQueriesAndBoundsBeforeDatabaseAccess() {
  var jdbc=mock(JdbcTemplate.class);var directory=new AccountDirectory(mock(ObjectProvider.class),jdbc);
  for(String query:new String[]{null,"x".repeat(255),"a\nb"})assertThatThrownBy(()->directory.list(query,0)).isInstanceOf(AccountFailure.class);
  for(int page:new int[]{-1,100001})assertThatThrownBy(()->directory.list("",page)).isInstanceOf(AccountFailure.class);
  verifyNoInteractions(jdbc);
  assertThat(directory.list("",0).accounts()).isEmpty();assertThat(directory.list("",0).hasNext()).isFalse();
 }
 @Test void localDirectoryUsesPrivateAuthenticationAndRejectsMalformedUpstreamPages() throws Exception {
  var body=new java.util.concurrent.atomic.AtomicReference<String>("{\"accounts\":[],\"page\":2,\"hasNext\":false}");
  var status=new java.util.concurrent.atomic.AtomicInteger(200);
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/internal/v1/accounts",exchange->{
   assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("query=a%2Bb&page=2");
   assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
   byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });server.start();
  try {
   var provider=mock(ObjectProvider.class);var origin="http://127.0.0.1:"+server.getAddress().getPort();
   doReturn(new IdentitySettings(origin,origin,"gateway","synthetic-verifier-secret-32-chars",java.time.Duration.ofSeconds(2),java.time.Duration.ofSeconds(2))).when(provider).getIfAvailable();
   var jdbc=mock(JdbcTemplate.class);var directory=new AccountDirectory(provider,jdbc);
   assertThat(directory.list("a+b",2).accounts()).isEmpty();verifyNoInteractions(jdbc);
   for(String malformed:new String[]{"{", "null", "{\"accounts\":null,\"page\":2}","{\"accounts\":[],\"page\":1}","{\"accounts\":[{}],\"page\":2}","x".repeat(262145)}) {
    body.set(malformed);assertThatThrownBy(()->directory.list("a+b",2)).isInstanceOf(AccountFailure.class);
   }
   status.set(503);body.set("private upstream error");assertThatThrownBy(()->directory.list("a+b",2)).isInstanceOf(AccountFailure.class).hasMessageNotContaining("private upstream error");
  } finally {server.stop(0);}
 }
}
