package com.lookahead.domain.cloud;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.exception.AccountFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
@RequestMapping("/internal/v1/cloud-sign-ins")
public class CloudSignInController {
    private final CloudSignInRegistry registry;
    private final software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognito;
    public CloudSignInController(CloudSignInRegistry registry,software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognito){this.registry=registry;this.cognito=cognito;}
    @GetMapping({"/inventory","/challenge","/profile"})
    public ApiResponse<?> read(@AuthenticationPrincipal CognitoIdentity identity,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");String proof=request.getHeader(CloudDomainTokenConverter.PROOF_HEADER);
        if(request.getRequestURI().endsWith("/profile"))return ApiResponse.success(registry.profile(identity,proof,null));
        return ApiResponse.success(request.getRequestURI().endsWith("/challenge")?registry.challenge(identity,proof,request.getHeader("X-LookAhead-Challenge")):registry.inventory(identity,proof));
    }
    @PostMapping({"/admit","/replace","/cancel","/revoke","/revoke-others","/label","/logout","/profile","/password"})
    public ApiResponse<?> mutate(@AuthenticationPrincipal CognitoIdentity identity,@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");String proof=request.getHeader(CloudDomainTokenConverter.PROOF_HEADER),challenge=request.getHeader("X-LookAhead-Challenge");
        String action=request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1);
        if(Set.of("admit","cancel","revoke-others","logout").contains(action)&&!body.isEmpty())throw invalid();
        return switch(action){
            case "profile" -> {if(!body.keySet().equals(Set.of("displayName"))||!(body.get("displayName") instanceof String name))throw invalid();yield ApiResponse.success(registry.profile(identity,proof,name));}
            case "password" -> {
                if(!body.keySet().equals(Set.of("currentPassword","newPassword","confirmPassword"))||!(body.get("currentPassword") instanceof String old)||!(body.get("newPassword") instanceof String next)||!next.equals(body.get("confirmPassword")))throw invalid();
                if(old.isEmpty()||old.length()>128||next.codePointCount(0,next.length())<15||next.codePointCount(0,next.length())>128)throw new AccountFailure(422,"INVALID_PASSWORD","Use a password with 15 to 128 characters.");
                if(next.equals(old))throw new AccountFailure(422,"PASSWORD_UNCHANGED","Choose a different password.");
                String token=request.getHeader("Authorization").substring(7);
                registry.changeCredentials(identity,proof,()->{
                    try {
                        cognito.changePassword(r->r.accessToken(token).previousPassword(old).proposedPassword(next));
                    } catch(software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException rejected){throw new AccountFailure(401,"INVALID_CURRENT_PASSWORD","Current password could not be verified.");}
                    catch(software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException rejected){throw new AccountFailure(422,"INVALID_PASSWORD","Password does not meet the account policy.");}
                    catch(RuntimeException uncertain){throw new AccountFailure(503,"SERVICE_UNAVAILABLE","Password change or sign-out could not be confirmed. Try signing in before retrying.");}
                });
                // Commit Domain revocation before the second provider operation: a provider
                // sign-out outage must not roll back the application's durable revocation.
                try {cognito.globalSignOut(r->r.accessToken(token));}
                catch(RuntimeException uncertain){throw new AccountFailure(503,"SERVICE_UNAVAILABLE","Password changed and application sign-ins ended, but provider sign-out could not be confirmed. Sign in again.");}
                yield ApiResponse.success(Map.of("reauthenticationRequired",true));
            }
            case "admit" -> ApiResponse.success(registry.admit(identity,proof));
            case "replace" -> ApiResponse.success(registry.replace(identity,proof,challenge,target(body)));
            case "cancel" -> {registry.cancel(identity,proof,challenge);yield ApiResponse.success(Map.of("cancelled",true));}
            case "revoke" -> ApiResponse.success(Map.of("reauthenticationRequired",registry.revoke(identity,proof,target(body))));
            case "revoke-others" -> {registry.revokeOthers(identity,proof);yield ApiResponse.success(Map.of("reauthenticationRequired",false));}
            case "logout" -> {registry.terminate(identity,proof);yield ApiResponse.success(Map.of("terminated",true));}
            case "label" -> {if(!body.keySet().equals(Set.of("label"))||!(body.get("label") instanceof String label))throw invalid();yield ApiResponse.success(registry.rename(identity,proof,label));}
            default -> throw invalid();
        };
    }
    private static UUID target(Map<String,Object> body){try{if(!body.keySet().equals(Set.of("signInId"))||!(body.get("signInId") instanceof String id))throw invalid();UUID value=UUID.fromString(id);if(!value.toString().equals(id))throw invalid();return value;}catch(IllegalArgumentException error){throw invalid();}}
    private static AccountFailure invalid(){return new AccountFailure(400,"INVALID_SIGN_IN_REQUEST","Invalid sign-in request.");}
}
