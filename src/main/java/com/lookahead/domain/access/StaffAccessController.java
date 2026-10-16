package com.lookahead.domain.access;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class StaffAccessController {
    private final StaffAccessService access;
    private final AccountDirectory directory;
    public StaffAccessController(StaffAccessService access, AccountDirectory directory){this.access=access;this.directory=directory;}
    @GetMapping("/api/v1/account/permissions")
    public ApiResponse<?> permissions(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(access.permissions(actor));}
    @GetMapping("/api/v1/admin/access")
    public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(access.list(actor));}
    @GetMapping("/api/v1/admin/access/catalog")
    public ApiResponse<?> catalog(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(access.catalog(actor));}
    @PostMapping("/api/v1/admin/access/lookup")
    public ApiResponse<?> lookup(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody Map<String,String> request,HttpServletResponse response){noStore(response);access.requireAccessReader(actor);return ApiResponse.success(directory.lookup(request.get("query")));}
    @PostMapping("/api/v1/admin/access")
    public ApiResponse<?> save(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody StaffAccessService.Change request,HttpServletResponse response){noStore(response);return ApiResponse.success(access.save(actor,request));}
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
