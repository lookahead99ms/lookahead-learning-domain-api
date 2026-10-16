package com.lookahead.domain;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.mockito.Mockito.*;

class DomainAccountControllerTest {
    @Test void repeatedReadsNeverClaimAndPostUsesOnlyVerifiedPrincipalEmail() throws Exception {
        var access = mock(StaffAccessService.class);
        var controller = new DomainAccountController(mock(AccountRepository.class),
                mock(ProtectedContentPolicy.class), mock(LocalAuthorAccess.class));
        controller.configureStaffAccess(access);
        var actor = new AccountPrincipal(UUID.randomUUID(), "untrusted@example.invalid", "Synthetic", true, false, "verified@example.invalid");
        controller.me(actor, new MockHttpServletResponse());
        controller.me(actor, new MockHttpServletResponse());
        verify(access, never()).claimVerified(any(), any());
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, "unused"));
        try {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/invitations/activate"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/invitations/activate")
                .param("verifiedEmail", "attacker@example.invalid").contentType("application/json").content("{\"email\":\"attacker@example.invalid\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        verify(access).claimVerified(actor, "verified@example.invalid");
        verify(access, times(3)).permissions(actor);
    }
}
