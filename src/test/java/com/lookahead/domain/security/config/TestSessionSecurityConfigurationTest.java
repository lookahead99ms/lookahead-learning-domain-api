package com.lookahead.domain.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lookahead.domain.controller.TestSessionController;
import com.lookahead.domain.db.store.testaccounts.TestAccountIdentities;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.filter.TestSessionFilter;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * DLV-969 S35, pinned for DLV-1016: the shipped filter chain of the internal test-session routes
 * over HTTP. Only a call with the Gateway BFF's secret reaches a route, and such a call needs no
 * CSRF token; any other call is refused with 401 and one log line before the CSRF check.
 */
class TestSessionSecurityConfigurationTest {
  static final UUID ACCOUNT = UUID.fromString("2c7e9a4d-1b3f-4c5e-8d9a-0b1c2d3e4f5a");
  static final String START = "/internal/v1/test-sessions/start",
      ACCOUNTS = "/internal/v1/test-sessions/accounts";

  @Test
  void onlyACallWithTheGatewaySecretReachesTheInternalRoutesAndItNeedsNoCsrfToken() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Application.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var http =
              MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
                  .apply(springSecurity())
                  .build();
          String body = "{\"accountId\":\"" + ACCOUNT + "\"}";
          try (var log = LogCapture.start()) {
            http.perform(
                    post(START)
                        .header(SignInBffSecret.HEADER, "secret")
                        .header(TestSessionFilter.HOLDER_HEADER, "sub-admin")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.accountId").value(ACCOUNT.toString()));
            http.perform(
                    get(ACCOUNTS)
                        .header(SignInBffSecret.HEADER, "secret")
                        .header(TestSessionFilter.HOLDER_HEADER, "sub-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountId").value(ACCOUNT.toString()));
            assertThat(log.lines("test-session.gateway-check")).isEmpty();
          }
          for (String wrong : new String[] {null, "", "wrong", "secretx"}) {
            try (var log = LogCapture.start()) {
              for (var call :
                  List.of(
                      post(START).contentType("application/json").content(body), get(ACCOUNTS))) {
                if (wrong != null) call.header(SignInBffSecret.HEADER, wrong);
                http.perform(call.header(TestSessionFilter.HOLDER_HEADER, "sub-admin"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(
                        content()
                            .string(
                                "{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED"
                                    + "\",\"message\":\"Sign"
                                    + " in to continue\"}"));
              }
              var lines = log.lines("test-session.gateway-check");
              assertThat(lines).hasSize(2);
              assertThat(lines.getFirst().getFormattedMessage())
                  .isEqualTo(
                      "Test-session route "
                          + START
                          + " refused: the call did not carry the Gateway secret");
              assertThat(LogCapture.keys(lines.getFirst()))
                  .containsEntry("event.outcome", "refused")
                  .containsEntry("reason", "GATEWAY_SECRET_INVALID")
                  .containsEntry("url.path", START);
              assertThat(lines.getLast().getFormattedMessage())
                  .isEqualTo(
                      "Test-session route "
                          + ACCOUNTS
                          + " refused: the call did not carry the Gateway secret");
              assertThat(log.lines("test-session.start"))
                  .as("a refused call never reaches the route")
                  .isEmpty();
            }
          }
        });
  }

  @Configuration
  @EnableWebSecurity
  @EnableWebMvc
  static class Application {
    /**
     * The real service with the Gateway BFF secret "secret"; only who may act as whom is answered
     * here.
     */
    @Bean
    TestSessionService sessions() {
      return new TestSessionService((TestAccountIdentities) null, null, "issuer", "secret") {
        @Override
        public AccountPrincipal actAs(String holder, UUID account) {
          if (!"sub-admin".equals(holder) || !ACCOUNT.equals(account))
            throw new AccountFailure(403, "TEST_SESSION_FORBIDDEN", "No");
          return new AccountPrincipal(
              ACCOUNT, "learner07@lookahead.dev", "Learner07", true, false, null);
        }

        @Override
        public List<Usable> usable(String holder) {
          return "sub-admin".equals(holder)
              ? List.of(
                  new Usable(
                      ACCOUNT,
                      "learner07@lookahead.dev",
                      "Learner07",
                      List.of(),
                      "Free · no study plan"))
              : List.of();
        }
      };
    }

    /** The shipped chain, from the shipped bean method. */
    @Bean
    @Order(0)
    SecurityFilterChain testSessionSecurity(HttpSecurity http, TestSessionService sessions)
        throws Exception {
      return new TestSessionSecurityConfiguration().testSessionSecurity(http, sessions);
    }

    @Bean
    TestSessionController testSessionController(TestSessionService sessions) {
      return new TestSessionController(sessions);
    }
  }
}
