package com.lookahead.domain.controller;

import static com.lookahead.domain.model.AuthorReviewModels.*;

import com.lookahead.domain.dto.ApiResponse;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.review.AuthorReviewService;
import com.lookahead.domain.validator.AuthorReviewRequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name = "app.deployment-environment", havingValue = "local")
@ConditionalOnProperty(name = "app.local-test.author-enabled", havingValue = "true")
public class AuthorReviewController {
  private final AuthorReviewService reviews;
  private final AuthorReviewRequestValidator shapes;

  public AuthorReviewController(AuthorReviewService reviews, AuthorReviewRequestValidator shapes) {
    this.reviews = reviews;
    this.shapes = shapes;
  }

  @GetMapping("/api/v1/author/review-artifacts")
  public ApiResponse<List<Artifact>> artifacts(
      @AuthenticationPrincipal AccountPrincipal actor, HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(reviews.artifacts(actor));
  }

  @GetMapping("/api/v1/author/review-artifacts/{artifactId}/events")
  public ApiResponse<History> history(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable String artifactId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor,
      HttpServletResponse response) {
    noStore(response);
    return ApiResponse.success(reviews.history(actor, artifactId, limit, shapes.cursor(cursor)));
  }

  @PostMapping("/api/v1/author/review-artifacts/{artifactId}/events")
  public ApiResponse<Receipt> submit(
      @AuthenticationPrincipal AccountPrincipal actor,
      @PathVariable String artifactId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      HttpServletResponse response) {
    noStore(response);
    var submission = shapes.submission(request.getQueryString(), body);
    var receipt = reviews.submit(actor, artifactId, shapes.key(key), submission);
    response.setStatus(receipt.replayed() ? 200 : 201);
    return ApiResponse.success(receipt);
  }

  private static void noStore(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }
}
