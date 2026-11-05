package com.lookahead.domain.membership;

import com.lookahead.domain.testaccounts.DevTestSessions;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** DLV-969 (owner, 2026-11-04): checkout passes the DEV test session's gate holder on as who sent the request. */
class MembershipControllerTest {
    @Test void checkoutPassesTheTestSessionsGateHolderAsTheRequester() {
        // Subclass mock maker: the image build runs without the inline agent.
        var service = mock(MembershipService.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS).strictness(Strictness.LENIENT));
        var controller = new MembershipController(service);
        var account = new AccountPrincipal(UUID.randomUUID(), "learner05@lookahead.dev", "Learner05", true);
        var buy = new MembershipService.Buy("learn", null, List.of());
        var answer = new ObjectMapper().createObjectNode().put("status", "pending");
        UUID holder = UUID.randomUUID();
        when(service.buy(account, account.accountId(), buy, "request-key-1", false, holder)).thenReturn(answer);
        when(service.buy(account, account.accountId(), buy, "request-key-2", false, null)).thenReturn(answer);
        var session = new MockHttpServletRequest();
        session.setAttribute(DevTestSessions.REQUESTER_ATTRIBUTE, holder);
        assertThat(controller.buy(account, buy, "request-key-1", session).data()).isEqualTo(answer);
        var plain = new MockHttpServletRequest();
        plain.setAttribute(DevTestSessions.REQUESTER_ATTRIBUTE, "not-an-id");
        assertThat(controller.buy(account, buy, "request-key-2", plain).data()).isEqualTo(answer);
        assertThat(controller.buy(account, buy, "request-key-2", null).data()).isEqualTo(answer);
        verify(service, times(2)).buy(account, account.accountId(), buy, "request-key-2", false, null);
    }

    /** DLV-969 (2026-11-04): a price body without {@code priceCents} reaches the price check (0: invalid, 422) instead of 400. */
    @Test void thePriceBodyReachesThePriceChangeWithOrWithoutItsCents() throws Exception {
        var service = mock(MembershipService.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new MembershipController(service))
            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
                com.lookahead.domain.testaccounts.TestAccountsControllerTest.bootJsonMapper()))
            .setControllerAdvice(new com.lookahead.learning.content.handler.AccountErrorHandler())
            .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                public boolean supportsParameter(org.springframework.core.MethodParameter parameter) { return parameter.getParameterType() == AccountPrincipal.class; }
                public Object resolveArgument(org.springframework.core.MethodParameter parameter, org.springframework.web.method.support.ModelAndViewContainer container,
                        org.springframework.web.context.request.NativeWebRequest request, org.springframework.web.bind.support.WebDataBinderFactory binders) { return owner; }
            }).build();
        when(service.price(owner, "learn", 0)).thenThrow(MembershipRules.invalid("Invalid price"));
        for (String body : new String[]{"{\"priceCents\":1200}", "{}"})
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/membership/plans/learn/price")
                .contentType("application/json").content(body));
        verify(service).price(owner, "learn", 1200);
        verify(service).price(owner, "learn", 0);
        assertThat(new MembershipController.Price(null).cents()).isZero();
        assertThat(new MembershipController.Price(5).cents()).isEqualTo(5);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/membership/plans/learn/price")
                .contentType("application/json").content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("MEMBERSHIP_INVALID"));
    }
}
