package com.lookahead.learning.content.controller;
import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.SupportFeedbackService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("accounts") @RequestMapping("/api/v1/support")
public class SupportController {
    private final SupportFeedbackService support;
    public SupportController(SupportFeedbackService support) { this.support = support; }
    @PostMapping public ResponseEntity<?> submit(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SupportFeedbackService.Feedback feedback) {
        var receipt = support.submit(principal.accountId().toString(),key,feedback);
        return ResponseEntity.status("accepted".equals(receipt.status()) ? 202 : 503).header("Cache-Control","no-store").body(ApiResponse.success(receipt));
    }
}
