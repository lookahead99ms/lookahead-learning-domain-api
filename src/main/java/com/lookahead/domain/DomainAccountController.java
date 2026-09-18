package com.lookahead.domain;

import com.lookahead.learning.content.dto.AccountView;
import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DomainAccountController {
    private final AccountRepository accounts;
    private final ProtectedContentPolicy publication;
    private final LocalAuthorAccess author;
    public DomainAccountController(AccountRepository accounts, ProtectedContentPolicy publication, LocalAuthorAccess author) {
        this.accounts = accounts; this.publication = publication; this.author = author;
    }
    @GetMapping("/api/v1/auth/me")
    public ApiResponse<AccountView> me(@AuthenticationPrincipal AccountPrincipal principal, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var grants = accounts.findTopicGrants(principal.accountId());
        return ApiResponse.success(new AccountView(principal.accountId(), principal.getUsername(), principal.displayName(),
                grants, publication.contentGrants(grants), author.allowed(principal)));
    }
}
