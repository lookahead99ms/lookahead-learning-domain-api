package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;

@RestController
public class TestAccountsController {
    private final TestAccountsService service;
    private final CreatedTestAccounts created;
    public TestAccountsController(TestAccountsService service,CreatedTestAccounts created){this.service=service;this.created=created;}
    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/internal/v1/test-accounts/csrf")
    public Map<String,String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
    @PostMapping("/internal/v1/test-accounts/seed") public Map<String,Integer> seed(){return Map.of("created",service.seed());}
    @GetMapping("/api/v1/admin/test-accounts") public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal actor){return ApiResponse.success(service.list(actor));}
    @GetMapping("/api/v1/admin/test-accounts/allowance") public ApiResponse<?> allowance(@AuthenticationPrincipal AccountPrincipal actor){return ApiResponse.success(created.allowance(actor));}
    @PostMapping("/api/v1/admin/test-accounts") public ApiResponse<?> create(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody CreatedTestAccounts.Create request){
        return ApiResponse.success(created.create(actor,request));
    }
    @DeleteMapping("/api/v1/admin/test-accounts/{id}") public ApiResponse<?> delete(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id){
        created.delete(actor,id);return ApiResponse.success(Map.of("deleted",true));
    }
    @PostMapping("/api/v1/admin/test-accounts/{id}/reset") public ApiResponse<?> reset(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@RequestBody TestAccountsService.Reset request){
        service.reset(actor,id,request);return ApiResponse.success(Map.of("reset",true));
    }
}
