package com.lookahead.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.lookahead.domain.dto.MembershipBuy;
import com.lookahead.domain.dto.MembershipPrice;
import com.lookahead.domain.security.filter.TestSessionFilter;
import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.membership.MembershipAccessSummaryService;
import com.lookahead.domain.service.membership.MembershipService;
import com.lookahead.domain.util.MembershipRules;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.quality.Strictness;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-969 (owner, 2026-11-04): checkout passes the DEV test session's gate holder on as who sent
 * the request.
 */
class MembershipControllerTest {
  @Test
  void checkoutPassesTheTestSessionsGateHolderAsTheRequester() {
    // Subclass mock maker: the image build runs without the inline agent.
    var service =
        mock(
            MembershipService.class,
            withSettings().mockMaker(MockMakers.SUBCLASS).strictness(Strictness.LENIENT));
    var controller =
        new MembershipController(
            service,
            mock(
                MembershipAccessSummaryService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS)));
    var account =
        new AccountPrincipal(UUID.randomUUID(), "learner05@lookahead.dev", "Learner05", true);
    var buy = new MembershipBuy("learn", null, List.of());
    var answer = new ObjectMapper().createObjectNode().put("status", "pending");
    UUID holder = UUID.randomUUID();
    when(service.buy(account, account.accountId(), buy, "request-key-1", false, holder))
        .thenReturn(answer);
    when(service.buy(account, account.accountId(), buy, "request-key-2", false, null))
        .thenReturn(answer);
    var session = new MockHttpServletRequest();
    session.setAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE, holder);
    assertThat(controller.buy(account, buy, "request-key-1", session).data()).isEqualTo(answer);
    var plain = new MockHttpServletRequest();
    plain.setAttribute(TestSessionFilter.REQUESTER_ATTRIBUTE, "not-an-id");
    assertThat(controller.buy(account, buy, "request-key-2", plain).data()).isEqualTo(answer);
    // A request with no such attribute at all: no requester either.
    assertThat(controller.buy(account, buy, "request-key-2", new MockHttpServletRequest()).data())
        .isEqualTo(answer);
    verify(service, times(2)).buy(account, account.accountId(), buy, "request-key-2", false, null);
  }

  /**
   * DLV-969 (2026-11-04): a price body without {@code priceCents} reaches the price check (0:
   * invalid, 422) instead of 400.
   */
  @Test
  void thePriceBodyReachesThePriceChangeWithOrWithoutItsCents() throws Exception {
    var service = mock(MembershipService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new MembershipController(
                    service,
                    mock(
                        MembershipAccessSummaryService.class,
                        withSettings().mockMaker(MockMakers.SUBCLASS))))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(TestAccountControllerTest.bootJsonMapper()))
            .setControllerAdvice(new AccountErrorHandler())
            .setCustomArgumentResolvers(
                new HandlerMethodArgumentResolver() {
                  public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == AccountPrincipal.class;
                  }

                  public Object resolveArgument(
                      MethodParameter parameter,
                      ModelAndViewContainer container,
                      NativeWebRequest request,
                      WebDataBinderFactory binders) {
                    return owner;
                  }
                })
            .build();
    when(service.price(owner, "learn", 0)).thenThrow(MembershipRules.invalid("Invalid price"));
    for (String body : new String[] {"{\"priceCents\":1200}", "{}"})
      mvc.perform(
          MockMvcRequestBuilders.post("/api/v1/admin/membership/plans/learn/price")
              .contentType("application/json")
              .content(body));
    verify(service).price(owner, "learn", 1200);
    verify(service).price(owner, "learn", 0);
    assertThat(new MembershipPrice(null).cents()).isZero();
    assertThat(new MembershipPrice(5).cents()).isEqualTo(5);
    mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/admin/membership/plans/learn/price")
                .contentType("application/json")
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
        .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MEMBERSHIP_INVALID"));
  }
}
