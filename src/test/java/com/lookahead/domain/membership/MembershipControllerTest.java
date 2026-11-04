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
}
