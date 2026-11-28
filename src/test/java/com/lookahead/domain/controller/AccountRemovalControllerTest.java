package com.lookahead.domain.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.dto.AccountRemovalConfirmation;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountRemovalService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountRemovalControllerTest {
  @Test
  void routesEachActionToTheServiceWithoutCaching() {
    var service = mock(AccountRemovalService.class);
    var controller = new AccountRemovalController(service);
    var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
    UUID id = UUID.randomUUID();
    var disable = new AccountRemovalConfirmation("DISABLE");
    var remove = new AccountRemovalConfirmation("DELETE");
    when(service.disable(owner, id, disable))
        .thenReturn(new AccountRemovalService.Result(id, "disabled"));
    when(service.enable(owner, id)).thenReturn(new AccountRemovalService.Result(id, "active"));
    when(service.remove(owner, id, remove))
        .thenReturn(new AccountRemovalService.Result(id, "removed"));
    assertThat(controller.disable(owner, id, disable).data())
        .isEqualTo(new AccountRemovalService.Result(id, "disabled"));
    assertThat(controller.enable(owner, id).data())
        .isEqualTo(new AccountRemovalService.Result(id, "active"));
    assertThat(controller.remove(owner, id, remove).data())
        .isEqualTo(new AccountRemovalService.Result(id, "removed"));
    var response = new MockHttpServletResponse();
    controller.noCache(response);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }
}
