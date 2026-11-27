package com.lookahead.domain.controller;

import com.lookahead.domain.service.signin.SignInPasswordService;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.security.token.SignInTokenConverter;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.validator.SignInRequestValidator;
import com.lookahead.domain.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The internal sign-in routes the Gateway BFF calls: one method per route; the request shapes are {@link SignInRequestValidator}'s. */
@RestController
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
@RequestMapping("/internal/v1/cloud-sign-ins")
public class SignInController {
    private final SignInService signInService;
    private final SignInPasswordService passwords;
    private final SignInRequestValidator shapes;
    public SignInController(SignInService signInService,SignInPasswordService passwords,SignInRequestValidator shapes){this.signInService=signInService;this.passwords=passwords;this.shapes=shapes;}

    @GetMapping("/inventory")
    public ApiResponse<?> inventory(@AuthenticationPrincipal SignInIdentity identity,HttpServletRequest request,HttpServletResponse response){
        noStore(response);return ApiResponse.success(signInService.inventory(identity,proof(request)));
    }
    // Exception, decided 2026-11-25 (DLV-1016): the challenge and profile reads answer the inventory when their address
    // is not written plainly (for example …/profil%65, which the HTTP firewall and the route matcher both accept). The
    // single handler these methods replace chose the read by the raw address and fell through to the inventory; a
    // method that only called its own service would answer the challenge or the profile there, a different answer.
    @GetMapping("/challenge")
    public ApiResponse<?> challenge(@AuthenticationPrincipal SignInIdentity identity,HttpServletRequest request,HttpServletResponse response){
        if(!shapes.plain(request.getRequestURI(),"challenge"))return inventory(identity,request,response);
        noStore(response);return ApiResponse.success(signInService.challenge(identity,proof(request),challenge(request)));
    }
    @GetMapping("/profile")
    public ApiResponse<?> profile(@AuthenticationPrincipal SignInIdentity identity,HttpServletRequest request,HttpServletResponse response){
        if(!shapes.plain(request.getRequestURI(),"profile"))return inventory(identity,request,response);
        noStore(response);return ApiResponse.success(signInService.profile(identity,proof(request),null));
    }

    @PostMapping("/admit")
    public ApiResponse<?> admit(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);shapes.empty(request.getRequestURI(),"admit",payload);
        return ApiResponse.success(signInService.admit(identity,proof(request)));
    }
    @PostMapping("/replace")
    public ApiResponse<?> replace(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);var target=shapes.signInId(request.getRequestURI(),"replace",payload);
        return ApiResponse.success(signInService.replace(identity,proof(request),challenge(request),target));
    }
    @PostMapping("/cancel")
    public ApiResponse<?> cancel(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);shapes.empty(request.getRequestURI(),"cancel",payload);
        signInService.cancel(identity,proof(request),challenge(request));return ApiResponse.success(Map.of("cancelled",true));
    }
    @PostMapping("/revoke")
    public ApiResponse<?> revoke(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);var target=shapes.signInId(request.getRequestURI(),"revoke",payload);
        return ApiResponse.success(Map.of("reauthenticationRequired",signInService.revoke(identity,proof(request),target)));
    }
    @PostMapping("/revoke-others")
    public ApiResponse<?> revokeOthers(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);shapes.empty(request.getRequestURI(),"revoke-others",payload);
        signInService.revokeOthers(identity,proof(request));return ApiResponse.success(Map.of("reauthenticationRequired",false));
    }
    @PostMapping("/label")
    public ApiResponse<?> label(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);String label=shapes.label(request.getRequestURI(),payload);
        return ApiResponse.success(signInService.rename(identity,proof(request),label));
    }
    @PostMapping("/logout")
    public ApiResponse<?> logout(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);shapes.empty(request.getRequestURI(),"logout",payload);
        signInService.terminate(identity,proof(request));return ApiResponse.success(Map.of("terminated",true));
    }
    @PostMapping("/profile")
    public ApiResponse<?> rename(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);String name=shapes.displayName(request.getRequestURI(),payload);
        return ApiResponse.success(signInService.profile(identity,proof(request),name));
    }
    @PostMapping("/password")
    public ApiResponse<?> password(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);var change=shapes.password(request.getRequestURI(),payload);
        passwords.change(identity,proof(request),change.currentPassword(),change.newPassword(),()->request.getHeader("Authorization").substring(7));
        return ApiResponse.success(Map.of("reauthenticationRequired",true));
    }
    @PostMapping("/preferences")
    public ApiResponse<?> preferences(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);String theme=shapes.theme(request.getRequestURI(),payload);
        return ApiResponse.success(signInService.preferences(identity,proof(request),theme));
    }
    @PostMapping("/links")
    public ApiResponse<?> links(@AuthenticationPrincipal SignInIdentity identity,@RequestBody(required=false) Object payload,HttpServletRequest request,HttpServletResponse response){
        noStore(response);var links=shapes.links(request.getRequestURI(),payload);
        return ApiResponse.success(signInService.links(identity,proof(request),links));
    }

    private static String proof(HttpServletRequest request){return request.getHeader(SignInTokenConverter.PROOF_HEADER);}
    private static String challenge(HttpServletRequest request){return request.getHeader("X-LookAhead-Challenge");}
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
