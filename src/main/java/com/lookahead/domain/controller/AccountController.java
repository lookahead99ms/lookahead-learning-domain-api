package com.lookahead.domain.controller;

import com.lookahead.domain.dto.AccountView;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.service.access.AccountViewService;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.StaffAccessService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {
    private final AccountViewService views;
    private final StaffAccessService access;
    public AccountController(AccountViewService views, StaffAccessService access) {
        this.views = views; this.access = access;
    }
    @PostMapping("/api/v1/auth/invitations/activate")
    public ApiResponse<AccountView> activateInvitation(@AuthenticationPrincipal AccountPrincipal principal, HttpServletResponse response) {
        access.claimVerified(principal, principal.verifiedEmail());
        return me(principal, response);
    }

    @GetMapping("/api/v1/auth/me")
    public ApiResponse<AccountView> me(@AuthenticationPrincipal AccountPrincipal principal, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(views.view(principal));
    }
}
