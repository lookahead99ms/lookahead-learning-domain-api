package com.lookahead.domain.cloud;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * DLV-969 S36.6: the person's own merge of accounts that share their verified email (an account route, not admin).
 * The request always answers 202 with nothing in it; the link answers 404 for anything but a live link of one of the
 * caller's accounts.
 */
@RestController
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
@RequestMapping("/api/v1/account/merge")
public class CloudMergeController {
    private final CloudMergeService merges;
    public CloudMergeController(CloudMergeService merges){this.merges=merges;}
    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @PostMapping("/request")
    public ResponseEntity<ApiResponse<?>> request(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody(required=false) Object ignored){
        merges.request(actor);
        return ResponseEntity.accepted().body(ApiResponse.success(Map.of()));
    }
    @GetMapping("/{token}")
    public ApiResponse<?> candidates(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable String token){
        return ApiResponse.success(merges.candidates(actor,token));
    }
    @PostMapping("/{token}")
    public ApiResponse<?> merge(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable String token,@RequestBody(required=false) Object payload){
        if(!(payload instanceof Map<?,?> body)||!body.keySet().equals(Set.of("keepAccountId"))||!(body.get("keepAccountId") instanceof String id))throw invalid();
        UUID keep;
        try{keep=UUID.fromString(id);}catch(IllegalArgumentException malformed){throw invalid();}
        if(!keep.toString().equals(id.toLowerCase(java.util.Locale.ROOT)))throw invalid();
        return ApiResponse.success(merges.merge(actor,token,keep));
    }
    private static AccountFailure invalid(){return new AccountFailure(400,"INVALID_MERGE_REQUEST","Send keepAccountId only.");}
}
