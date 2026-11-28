package com.lookahead.domain.controller;

import static org.mockito.Mockito.*;

import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.access.AccountViewService;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountControllerTest {
  /**
   * DLV-968: auth/me lists courses; the staff documents scope is governed by
   * permissions.previewDocuments instead.
   */
  @Test
  void meListsCurriculumGrantsWithoutTheStaffScope() {
    var accounts = mock(AccountGrantService.class);
    var publication = mock(ProtectedContentManifestService.class);
    var controller =
        new AccountController(
            new AccountViewService(
                accounts,
                publication,
                mock(AuthorPreviewLocalService.class),
                mock(StaffAccessService.class)),
            mock(StaffAccessService.class));
    var actor = new AccountPrincipal(UUID.randomUUID(), "admin", "Admin", true);
    when(accounts.findTopicGrants(actor.accountId()))
        .thenReturn(Set.of("staff:documents", "learn:core-java"));
    when(publication.contentGrants(Set.of("learn:core-java")))
        .thenReturn(Set.of("learn:core-java:intro"));
    var view = controller.me(actor, new MockHttpServletResponse()).data();
    Assertions.assertThat(view.topicGrants()).containsExactly("learn:core-java");
    Assertions.assertThat(view.contentGrants()).containsExactly("learn:core-java:intro");
    verify(publication).contentGrants(Set.of("learn:core-java"));
  }

  @Test
  void repeatedReadsNeverClaimAndPostUsesOnlyVerifiedPrincipalEmail() throws Exception {
    var access = mock(StaffAccessService.class);
    var controller =
        new AccountController(
            new AccountViewService(
                mock(AccountGrantService.class),
                mock(ProtectedContentManifestService.class),
                mock(AuthorPreviewLocalService.class),
                access),
            access);
    var actor =
        new AccountPrincipal(
            UUID.randomUUID(),
            "untrusted@example.invalid",
            "Synthetic",
            true,
            false,
            "verified@example.invalid");
    controller.me(actor, new MockHttpServletResponse());
    controller.me(actor, new MockHttpServletResponse());
    verify(access, never()).claimVerified(any(), any());
    var http =
        MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(actor, "unused"));
    try {
      http.perform(MockMvcRequestBuilders.get("/api/v1/auth/invitations/activate"))
          .andExpect(MockMvcResultMatchers.status().isMethodNotAllowed());
      http.perform(
              MockMvcRequestBuilders.post("/api/v1/auth/invitations/activate")
                  .param("verifiedEmail", "attacker@example.invalid")
                  .contentType("application/json")
                  .content("{\"email\":\"attacker@example.invalid\"}"))
          .andExpect(MockMvcResultMatchers.status().isOk())
          .andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
    } finally {
      SecurityContextHolder.clearContext();
    }
    verify(access).claimVerified(actor, "verified@example.invalid");
    verify(access, times(3)).permissions(actor);
  }
}
