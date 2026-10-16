package com.lookahead.domain.access;
import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class AdminAccountDirectoryController {
    private final AdminAccountDirectory directory;
    public AdminAccountDirectoryController(AdminAccountDirectory directory){this.directory=directory;}
    @GetMapping("/api/v1/admin/accounts")
    public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal actor,
            @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.success(directory.list(actor,query,page));
    }
}
