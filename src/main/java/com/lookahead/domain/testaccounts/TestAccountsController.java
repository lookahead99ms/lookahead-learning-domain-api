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
    public TestAccountsController(TestAccountsService service){this.service=service;}
    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @PostMapping("/internal/v1/test-accounts/seed") public Map<String,Integer> seed(){return Map.of("created",service.seed());}
    @GetMapping("/api/v1/admin/test-accounts") public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal actor){return ApiResponse.success(service.list(actor));}
    @PostMapping("/api/v1/admin/test-accounts/{id}/reset") public ApiResponse<?> reset(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@RequestBody TestAccountsService.Reset request){
        service.reset(actor,id,request);return ApiResponse.success(Map.of("reset",true));
    }
}
