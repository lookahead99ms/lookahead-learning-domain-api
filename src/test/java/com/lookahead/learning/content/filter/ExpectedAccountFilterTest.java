package com.lookahead.learning.content.filter;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
class ExpectedAccountFilterTest {
    @Test void supportDraftCannotFollowAChangedBrowserAccount() throws Exception {
        var owner=UUID.randomUUID();var principal=new AccountPrincipal(owner,"synthetic","Synthetic", true);
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
            var request=new MockHttpServletRequest("POST","/api/v1/support");request.addHeader("X-LookAhead-Account",UUID.randomUUID().toString());
            var response=new MockHttpServletResponse();var called=new AtomicBoolean();
            new ExpectedAccountFilter().doFilter(request,response,(a,b)->called.set(true));
            assertThat(response.getStatus()).isEqualTo(401);assertThat(response.getContentAsString()).contains("ACCOUNT_CHANGED");assertThat(called.get()).isFalse();
        } finally {SecurityContextHolder.clearContext();}
    }
    @Test void matchingSupportOwnerMayContinueToNormalAuthorization() throws Exception {
        var owner=UUID.randomUUID();var principal=new AccountPrincipal(owner,"synthetic","Synthetic", true);
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
            var request=new MockHttpServletRequest("POST","/api/v1/support");request.addHeader("X-LookAhead-Account",owner.toString());
            var response=new MockHttpServletResponse();var called=new AtomicBoolean();
            new ExpectedAccountFilter().doFilter(request,response,(a,b)->called.set(true));assertThat(called.get()).isTrue();
        } finally {SecurityContextHolder.clearContext();}
    }
}
