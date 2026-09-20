package com.lookahead.domain.review;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.lookahead.domain.review.ReviewModels.*;

@RestController
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name="app.deployment-environment",havingValue="local")
@ConditionalOnProperty(name="app.local-test.author-enabled",havingValue="true")
public class AuthorReviewController {
    private final AuthorReviewService reviews;
    public AuthorReviewController(AuthorReviewService reviews){this.reviews=reviews;}
    @GetMapping("/api/v1/author/review-artifacts")
    public ApiResponse<List<Artifact>> artifacts(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response) {
        noStore(response);return ApiResponse.success(reviews.artifacts(actor));
    }
    @GetMapping("/api/v1/author/review-artifacts/{artifactId}/events")
    public ApiResponse<History> history(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable String artifactId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor,HttpServletResponse response) {
        noStore(response);return ApiResponse.success(reviews.history(actor,artifactId,limit,cursor==null?null:uuid(cursor)));
    }
    @PostMapping("/api/v1/author/review-artifacts/{artifactId}/events")
    public ApiResponse<Receipt> submit(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable String artifactId,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody Map<String,Object> body,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response) {
        noStore(response);
        if(request.getQueryString()!=null)throw invalid();
        if(body==null||!Set.of("artifactVersion","contentHash","ticketId","decision","comment","supersedesEventId").containsAll(body.keySet())
                || !body.keySet().containsAll(Set.of("artifactVersion","contentHash","ticketId","decision","comment")))throw invalid();
        try {
            var submission=new Submission(text(body,"artifactVersion"),text(body,"contentHash"),text(body,"ticketId"),Decision.valueOf(text(body,"decision")),text(body,"comment"),body.get("supersedesEventId")==null?null:uuid(text(body,"supersedesEventId")));
            var receipt=reviews.submit(actor,artifactId,uuid(key),submission);response.setStatus(receipt.replayed()?200:201);return ApiResponse.success(receipt);
        } catch(IllegalArgumentException failure){throw invalid();}
    }
    private static String text(Map<String,Object> body,String key){if(!(body.get(key) instanceof String text))throw invalid();return text;}
    private static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}catch(IllegalArgumentException|NullPointerException failure){throw invalid();}}
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_REVIEW_REQUEST","Review request is invalid");}
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
