package com.lookahead.domain.access;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** DLV-969: owner-only admission of waiting accounts; secured with the other /api/v1/admin/access routes. */
@RestController
public class AccountAdmissionController {
    private final AccountAdmissionService admission;
    public AccountAdmissionController(AccountAdmissionService admission){this.admission=admission;}
    @GetMapping("/api/v1/admin/access/waiting")
    public ApiResponse<?> waiting(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(admission.waiting(actor));}
    @PostMapping("/api/v1/admin/access/admit")
    public ApiResponse<?> admit(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody AccountAdmissionService.Request request,HttpServletResponse response){noStore(response);return ApiResponse.success(admission.admit(actor,request));}
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
