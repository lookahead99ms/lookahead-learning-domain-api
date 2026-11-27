package com.lookahead.domain.controller;

import com.lookahead.domain.dto.DevAccessAdmission;
import com.lookahead.domain.service.access.DevAccessService;
import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** DLV-969: owner-only admission of waiting accounts; secured with the other /api/v1/admin/access routes. */
@RestController
public class DevAccessController {
    private final DevAccessService devAccess;
    public DevAccessController(DevAccessService devAccess){this.devAccess=devAccess;}
    @GetMapping("/api/v1/admin/access/waiting")
    public ApiResponse<?> waiting(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(devAccess.waiting(actor));}
    @PostMapping("/api/v1/admin/access/admit")
    public ApiResponse<?> admit(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody DevAccessAdmission request,HttpServletResponse response){noStore(response);return ApiResponse.success(devAccess.admit(actor,request));}
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
