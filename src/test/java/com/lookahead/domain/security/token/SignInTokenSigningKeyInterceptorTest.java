package com.lookahead.domain.security.token;

import com.lookahead.domain.log.LogCapture;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One upstream line per download of the keys Cognito signs sign-in tokens with; the answer and any failure pass through. */
class SignInTokenSigningKeyInterceptorTest {
    final SignInTokenSigningKeyInterceptor interceptor = new SignInTokenSigningKeyInterceptor();
    final MockClientHttpRequest download = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://cognito-idp.us-east-2.amazonaws.com/pool/.well-known/jwks.json"));

    @Test void aSuccessfulDownloadIsReturnedUnchangedAndLoggedOnce() throws IOException {
        var answer = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        try (var log = LogCapture.start()) {
            assertThat(interceptor.intercept(download, new byte[0], (request, body) -> answer)).isSameAs(answer);
            assertThat(log.lines("upstream.call")).hasSize(1);
            assertThat(LogCapture.keys(log.lines("upstream.call").getFirst())).containsEntry("upstream.operation", "GetSigningKeys")
                    .containsEntry("upstream.service", "cognito").containsEntry("event.outcome", "success");
        }
    }

    @Test void anAnswerOtherThan200IsReturnedUnchangedAndLoggedAsAFailure() throws IOException {
        var answer = new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE);
        try (var log = LogCapture.start()) {
            assertThat(interceptor.intercept(download, new byte[0], (request, body) -> answer)).isSameAs(answer);
            assertThat(log.lines("upstream.call")).hasSize(1);
            assertThat(LogCapture.keys(log.lines("upstream.call").getFirst())).containsEntry("event.outcome", "failure").containsEntry("upstream.status", 503);
        }
    }

    @Test void aFailedDownloadIsLoggedAndTheSameFailureIsThrown() {
        var failure = new IOException("connection reset");
        ClientHttpRequestExecution failing = (request, body) -> { throw failure; };
        try (var log = LogCapture.start()) {
            assertThatThrownBy(() -> interceptor.intercept(download, new byte[0], failing)).isSameAs(failure);
            assertThat(log.lines("upstream.call")).hasSize(1);
            assertThat(LogCapture.keys(log.lines("upstream.call").getFirst())).containsEntry("event.outcome", "failure");
        }
    }
}
