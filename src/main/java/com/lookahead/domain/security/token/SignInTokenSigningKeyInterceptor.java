package com.lookahead.domain.security.token;

import com.lookahead.domain.log.EventLog;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Sits on the download of the token signing keys: the public keys Cognito signs sign-in tokens
 * with, which the token decoder fetches to check a token's signature (they are not TLS keys). It
 * times each download and writes one {@code lookahead.upstream} line for it; the decoder caches the
 * keys between downloads.
 */
// Exception, decided 2026-11-25 (DLV-1016): this class only writes a log line. The download is made
// inside the token
// decoder (library code that is handed a RestTemplate), so no class of ours makes the call and
// could write the line
// itself; a request interceptor is the one place where the call can be timed. The interceptor
// cannot be written inside
// the configuration that builds the decoder, because a configuration class only creates beans.
public final class SignInTokenSigningKeyInterceptor implements ClientHttpRequestInterceptor {
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    long started = EventLog.started();
    try {
      var response = execution.execute(request, body);
      int status = response.getStatusCode().value();
      if (status == 200) EventLog.upstreamDone("Cognito", "GetSigningKeys", started);
      else EventLog.upstreamFailed("Cognito", "GetSigningKeys", started, null, status, null);
      return response;
    } catch (IOException | RuntimeException failure) {
      EventLog.upstreamFailed("Cognito", "GetSigningKeys", started, failure);
      throw failure;
    }
  }
}
