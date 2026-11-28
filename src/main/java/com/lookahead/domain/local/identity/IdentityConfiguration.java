package com.lookahead.domain.local.identity;

import com.lookahead.domain.config.HttpConnectionFactory;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.validator.IdentityConnectionValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The beans of the Local sign-in path through the Identity service: its connection values, the
 * token check client, the account directory client, the operator client, the token decoder and the
 * token converter. Each exists only when {@code app.deployment-environment} is {@code local}.
 */
@Configuration
public class IdentityConfiguration {
  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  IdentityConnection identityConnection(Environment environment) {
    return IdentityConnectionValidator.validated(environment);
  }

  /**
   * The one HTTP connection to the Identity service, with the connection values' timeouts and no
   * redirects.
   */
  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  public RestClient identityRestClient(IdentityConnection connection) {
    return RestClient.builder()
        .requestFactory(
            HttpConnectionFactory.noRedirects(
                connection.connectTimeout(), connection.readTimeout()))
        .build();
  }

  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  IdentityAccountClient identityAccounts(
      IdentityConnection connection, RestClient identityRestClient) {
    return new IdentityAccountClient(connection, identityRestClient);
  }

  /**
   * The operator calls of test-account and learner maintenance. It is both sign-in clients of Local
   * (DLV-1016).
   */
  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  IdentityOperatorClient identityOperatorClient(
      IdentityConnection connection, RestClient identityRestClient, Environment environment) {
    return new IdentityOperatorClient(connection, identityRestClient, environment);
  }

  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  IdentityVerificationClient identityVerification(
      IdentityConnection connection, RestClient identityRestClient, ObjectMapper mapper) {
    return new IdentityVerificationClient(connection, identityRestClient, mapper);
  }

  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  JwtDecoder identityDecoder(IdentityConnection connection) {
    var factory =
        HttpConnectionFactory.noRedirects(connection.connectTimeout(), connection.readTimeout());
    var decoder =
        NimbusJwtDecoder.withJwkSetUri(connection.upstream() + "/oauth2/jwks")
            .restOperations(new RestTemplate(factory))
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(connection.issuer()));
    return decoder;
  }

  @Bean
  @ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
  Converter<Jwt, AbstractAuthenticationToken> localAuthenticationConverter(
      IdentityConnection connection,
      IdentityVerificationClient identity,
      AccountGrantService accounts) {
    return new IdentityTokenConverter(connection, identity, accounts);
  }
}
