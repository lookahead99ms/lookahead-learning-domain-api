package com.lookahead.domain.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lookahead.domain.security.token.SignInProviderFixture;
import com.lookahead.domain.security.token.SignInTokenVerifierTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;

/** Actual DEV/PROD filter chains; controlled JWT/provider boundaries, no database or network. */
class SignInSecurityConfigurationChainTest {
  private static final String SECRET = SignInTokenVerifierTest.BFF_SECRET.value();
  private static final String PATH = "/internal/v1/cloud-sign-ins/admit";
  static final AtomicBoolean revoked = new AtomicBoolean();
  static SignInProviderFixture provider;

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void admissionAlwaysRequiresBearerVerificationAndServerGatewayCredential(String environment) {
    revoked.set(false);
    provider = new SignInProviderFixture();
    provider.response =
        request -> {
          if (revoked.get())
            throw NotAuthorizedException.builder().message("synthetic-revocation").build();
          return GetUserResponse.builder()
              .username("synthetic-user")
              .userAttributes(
                  AttributeType.builder().name("sub").value("synthetic-provider-subject").build())
              .build();
        };
    new WebApplicationContextRunner()
        .withUserConfiguration(
            SignInSecurityConfigurationProviderTest.Application.class, Fixtures.class)
        .withPropertyValues(
            "spring.profiles.active=accounts,resource",
            "app.deployment-environment=" + environment,
            "app.cognito.issuer=" + SignInTokenVerifierTest.TRUST.issuer(),
            "app.cognito.region=us-east-2",
            "app.cognito.client-id=" + SignInTokenVerifierTest.TRUST.clientId(),
            "app.cognito.scope-prefix=lookahead",
            "app.cognito.gateway-secret=" + SECRET)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var http =
              MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
                  .apply(springSecurity())
                  .build();
          http.perform(post(PATH).header("Authorization", "Bearer synthetic-access-token"))
              .andExpect(status().isUnauthorized());
          for (String wrong : new String[] {"", "wrong", SECRET + "x"}) {
            http.perform(
                    post(PATH)
                        .header("Authorization", "Bearer synthetic-access-token")
                        .header("X-LookAhead-Gateway-Secret", wrong))
                .andExpect(status().isUnauthorized());
          }
          http.perform(post(PATH).header("X-LookAhead-Gateway-Secret", SECRET))
              .andExpect(status().isForbidden());
          http.perform(
                  post(PATH)
                      .header("X-LookAhead-Gateway-Secret", SECRET)
                      .header("Authorization", "Bearer rejected-token"))
              .andExpect(status().isUnauthorized());
          // Browser-attached credentials cannot replace either required header.
          http.perform(
                  post(PATH)
                      .cookie(
                          new Cookie("Authorization", "synthetic-access-token"),
                          new Cookie("X-LookAhead-Gateway-Secret", SECRET)))
              .andExpect(status().isForbidden());
          assertThat(provider.calls.get()).isZero();
          http.perform(
                  post(PATH)
                      .header("Authorization", "Bearer synthetic-access-token")
                      .header("X-LookAhead-Gateway-Secret", SECRET))
              .andExpect(status().isOk())
              .andExpect(header().doesNotExist("Set-Cookie"));
          revoked.set(true);
          http.perform(
                  post(PATH)
                      .header("Authorization", "Bearer synthetic-access-token")
                      .header("X-LookAhead-Gateway-Secret", SECRET))
              .andExpect(status().isUnauthorized());
          revoked.set(false);
          http.perform(
                  post(PATH)
                      .header("Authorization", "Bearer synthetic-access-token")
                      .header("X-LookAhead-Gateway-Secret", SECRET))
              .andExpect(status().isOk());
          assertThat(provider.calls.get()).isEqualTo(3);
        });
  }

  /**
   * DLV-1016, the fact behind the exception comment in SignInController: an address whose route
   * name is percent-encoded (…/profil%65) is accepted by the HTTP firewall of the shipped chain and
   * by the route matcher, so it reaches the route's method, which then decides by the raw address.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void aPercentEncodedRouteNamePassesTheFirewallAndReachesTheRoute(String environment) {
    revoked.set(false);
    provider = new SignInProviderFixture();
    provider.response =
        request ->
            GetUserResponse.builder()
                .username("synthetic-user")
                .userAttributes(
                    AttributeType.builder().name("sub").value("synthetic-provider-subject").build())
                .build();
    new WebApplicationContextRunner()
        .withUserConfiguration(
            SignInSecurityConfigurationProviderTest.Application.class, Fixtures.class)
        .withPropertyValues(
            "spring.profiles.active=accounts,resource",
            "app.deployment-environment=" + environment,
            "app.cognito.issuer=" + SignInTokenVerifierTest.TRUST.issuer(),
            "app.cognito.region=us-east-2",
            "app.cognito.client-id=" + SignInTokenVerifierTest.TRUST.clientId(),
            "app.cognito.scope-prefix=lookahead",
            "app.cognito.gateway-secret=" + SECRET)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var http =
              MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
                  .apply(springSecurity())
                  .build();
          for (String address :
              new String[] {
                "/internal/v1/cloud-sign-ins/profile", "/internal/v1/cloud-sign-ins/profil%65"
              })
            http.perform(
                    get(URI.create(address))
                        .header("Authorization", "Bearer synthetic-access-token")
                        .header("X-LookAhead-Gateway-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(content().string("synthetic-profile"));
          // Without the Gateway BFF's secret the same address is refused by the chain, encoded or
          // not.
          http.perform(
                  get(URI.create("/internal/v1/cloud-sign-ins/profil%65"))
                      .header("Authorization", "Bearer synthetic-access-token"))
              .andExpect(status().isUnauthorized());
        });
  }

  @Configuration
  static class Fixtures {
    @Bean
    @Primary
    CognitoIdentityProviderClient fixtureProvider() {
      return provider.client;
    }

    @Bean
    @Primary
    JwtDecoder fixtureDecoder() {
      return token -> {
        if (!token.equals("synthetic-access-token"))
          throw new BadJwtException("Rejected synthetic token");
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .issuer(SignInTokenVerifierTest.TRUST.issuer())
            .subject("synthetic-provider-subject")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("token_use", "access")
            .claim("client_id", SignInTokenVerifierTest.TRUST.clientId())
            .claim("origin_jti", "synthetic-family")
            .claim("auth_time", Instant.now())
            .claim(
                "scope",
                "aws.cognito.signin.user.admin lookahead/account lookahead/content"
                    + " lookahead/support")
            .build();
      };
    }

    @Bean
    AdmissionProbe admissionProbe() {
      return new AdmissionProbe();
    }
  }

  @RestController
  static class AdmissionProbe {
    @PostMapping(PATH)
    String admit() {
      return "synthetic-admitted";
    }

    @GetMapping("/internal/v1/cloud-sign-ins/profile")
    String profile() {
      return "synthetic-profile";
    }
  }
}
