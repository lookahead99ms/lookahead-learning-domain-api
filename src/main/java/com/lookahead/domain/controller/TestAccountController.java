package com.lookahead.domain.controller;

import com.lookahead.domain.dto.TestAccountCreate;
import com.lookahead.domain.dto.TestAccountReset;
import com.lookahead.domain.service.testaccounts.TestAccountCreationService;
import com.lookahead.domain.service.testaccounts.TestAccountAdminService;
import com.lookahead.domain.service.testaccounts.TestAccountService;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.validator.TestAccountCreateRequestValidator;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;

@RestController
public class TestAccountController {
    private final TestAccountService service;
    private final TestAccountCreationService creation;
    private final TestAccountAdminService admin;
    private final TestAccountCreateRequestValidator shapes;
    public TestAccountController(TestAccountService service,TestAccountCreationService creation,TestAccountAdminService admin,TestAccountCreateRequestValidator shapes){this.service=service;this.creation=creation;this.admin=admin;this.shapes=shapes;}
    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/internal/v1/test-accounts/csrf")
    public Map<String,String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
    @PostMapping("/internal/v1/test-accounts/seed") public Map<String,Integer> seed(){return Map.of("created",service.seed());}
    @GetMapping("/api/v1/admin/test-accounts") public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal actor){return ApiResponse.success(service.list(actor));}
    @GetMapping("/api/v1/admin/test-accounts/allowance") public ApiResponse<?> allowance(@AuthenticationPrincipal AccountPrincipal actor){
        return ApiResponse.success(admin.allowance(actor));
    }
    /** DLV-969 S35: {@code "roster": true} creates the next DEV roster accounts, on the route the Gateway already forwards. */
    @PostMapping("/api/v1/admin/test-accounts") public ApiResponse<?> create(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody TestAccountCreate request){
        return ApiResponse.success(admin.create(actor,shapes.kind(request),request));
    }
    @DeleteMapping("/api/v1/admin/test-accounts/{id}") public ApiResponse<?> delete(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id){
        creation.delete(actor,id);return ApiResponse.success(Map.of("deleted",true));
    }
    @PostMapping("/api/v1/admin/test-accounts/{id}/reset") public ApiResponse<?> reset(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@RequestBody TestAccountReset request){
        service.reset(actor,id,request);return ApiResponse.success(Map.of("reset",true));
    }
}
