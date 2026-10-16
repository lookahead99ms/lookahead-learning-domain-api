package com.lookahead.domain.membership;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import tools.jackson.databind.node.ObjectNode;

@RestController
public class MembershipController {
    private final MembershipService service;
    public MembershipController(MembershipService service){this.service=service;}
    private AccessSummaryService summaries;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureSummaries(AccessSummaryService summaries){this.summaries=summaries;}
    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/api/v1/membership/plans") public ApiResponse<?> catalog(){return ApiResponse.success(service.catalog());}
    @GetMapping("/api/v1/sales/active") public ApiResponse<?> sale(){return ApiResponse.success(service.activeSale());}
    @GetMapping("/api/v1/account/access-records") public ApiResponse<?> access(@AuthenticationPrincipal AccountPrincipal actor){return ApiResponse.success(service.access(actor,actor.accountId()));}
    @GetMapping("/api/v1/account/access-summary") public ApiResponse<?> summary(@AuthenticationPrincipal AccountPrincipal actor){return ApiResponse.success(summaries.get(actor,actor.accountId()));}
    @GetMapping("/api/v1/admin/accounts/{id}/access-summary") public ApiResponse<?> summaryFor(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id){return ApiResponse.success(summaries.get(actor,id));}
    @PostMapping("/api/v1/account/purchases/quote") public ApiResponse<?> quote(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody MembershipService.Buy request){return ApiResponse.success(service.quote(actor,request));}
    @PostMapping("/api/v1/account/purchases") public ApiResponse<?> buy(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody MembershipService.Buy request,@RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.success(service.buy(actor,actor.accountId(),request,key,false));}
    @GetMapping("/api/v1/account/purchases/{id}") public ApiResponse<?> get(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id){return ApiResponse.success(service.get(actor,id));}
    @GetMapping("/api/v1/admin/accounts/{id}/access-records") public ApiResponse<?> accessFor(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id){return ApiResponse.success(service.access(actor,id));}
    @PostMapping("/api/v1/admin/accounts/{id}/plan-upgrades") public ApiResponse<?> upgrade(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@RequestBody MembershipService.Buy request,@RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.success(service.buy(actor,id,request,key,true));}
    @PostMapping("/api/v1/admin/sales") public ApiResponse<?> start(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody MembershipService.Sale sale){return ApiResponse.success(service.startSale(actor,sale));}
    @PostMapping("/api/v1/admin/sales/{id}/stop") public ApiResponse<?> stop(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id){service.stopSale(actor,id);return ApiResponse.success(Map.of("stopped",true));}
    public record Price(int priceCents) {}
    @PostMapping("/api/v1/admin/membership/plans/{id}/price") public ApiResponse<?> price(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable String id,@RequestBody Price price){return ApiResponse.success(service.price(actor,id,price.priceCents()));}
}
