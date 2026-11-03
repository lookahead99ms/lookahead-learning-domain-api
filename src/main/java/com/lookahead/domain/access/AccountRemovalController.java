package com.lookahead.domain.access;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** DLV-969: the owner's Disable (type DISABLE), Enable and Delete (type DELETE) of a DEV sign-in account. */
@RestController
public class AccountRemovalController {
    private final AccountRemovalService removal;
    public AccountRemovalController(AccountRemovalService removal) { this.removal=removal; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @PostMapping("/api/v1/admin/accounts/{id}/disable")
    public ApiResponse<?> disable(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestBody AccountRemovalService.Confirmation request) {
        return ApiResponse.success(removal.disable(actor,id,request));
    }
    @PostMapping("/api/v1/admin/accounts/{id}/enable")
    public ApiResponse<?> enable(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id) {
        return ApiResponse.success(removal.enable(actor,id));
    }
    @PostMapping("/api/v1/admin/accounts/{id}/remove")
    public ApiResponse<?> remove(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestBody AccountRemovalService.Confirmation request) {
        return ApiResponse.success(removal.remove(actor,id,request));
    }
}
