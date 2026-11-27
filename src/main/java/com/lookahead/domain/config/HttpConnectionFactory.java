package com.lookahead.domain.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * DLV-1016: the one way an outgoing HTTP connection of this application is built (the Identity connection, the Identity
 * key download and the Cognito key download each had their own copy): the JDK client with a connect limit, a read
 * limit and no redirects.
 */
public final class HttpConnectionFactory {
    private HttpConnectionFactory() {}

    public static JdkClientHttpRequestFactory noRedirects(Duration connectTimeout, Duration readTimeout) {
        var client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
