package com.lookahead.domain.access;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class RealAccountMaintenanceController {
    private final RealAccountMaintenance maintenance;
    public RealAccountMaintenanceController(RealAccountMaintenance maintenance) { this.maintenance=maintenance; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @PostMapping("/api/v1/admin/accounts/{id}/downgrade")
    public ApiResponse<?> downgrade(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestBody RealAccountMaintenance.Confirmation request) {
        maintenance.downgrade(actor,id,request);
        return ApiResponse.success(Map.of("downgraded",true));
    }
    @PostMapping("/api/v1/admin/accounts/{id}/delete")
    public ApiResponse<?> delete(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestBody RealAccountMaintenance.Confirmation request) {
        maintenance.deleteLearner(actor,id,request);
        return ApiResponse.success(Map.of("deleted",true));
    }
}
