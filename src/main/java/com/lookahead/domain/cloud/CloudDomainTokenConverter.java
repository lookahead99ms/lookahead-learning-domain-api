package com.lookahead.domain.cloud;

import com.lookahead.domain.security.IdentityUnavailableException;
import com.lookahead.learning.content.exception.AccountFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;

public final class CloudDomainTokenConverter implements Converter<Jwt,AbstractAuthenticationToken> {
    public static final String PROOF_HEADER="X-LookAhead-SignIn-Proof";
    private final CognitoTokenVerifier verifier;
    private final CloudSignInRegistry registry;
    private final HttpServletRequest request;
    public CloudDomainTokenConverter(CognitoTokenVerifier verifier,CloudSignInRegistry registry,HttpServletRequest request){this.verifier=verifier;this.registry=registry;this.request=request;}
    @Override public AbstractAuthenticationToken convert(Jwt token){
        String proof=request.getHeader(PROOF_HEADER);
        // DLV-969 decision B: an API sign-in token is admissible only alongside the Gateway's per-sign-in proof,
        // which registry.authenticate then binds to this exact sign-in (a forged or stale proof is refused there).
        var identity=verifier.verifyForApiCall(token,proof!=null && !proof.isBlank() && proof.length()<=512);
        try {
            var principal=registry.authenticate(identity,proof);
            return UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("SCOPE_account"),new SimpleGrantedAuthority("SCOPE_content"),new SimpleGrantedAuthority("SCOPE_support")));
        } catch(AccountFailure denied){
            if(denied.status()>=500){unavailable(denied.code(),null);throw new IdentityUnavailableException();}
            throw CognitoTokenVerifier.invalid(com.lookahead.domain.Events.safe(denied.code()));
        }
        catch(RuntimeException unavailable){unavailable("UNEXPECTED",unavailable);throw new IdentityUnavailableException();}
    }

    /*
     * DLV-969 (owner, 2026-11-04): the 401 or 503 of an API call used to say only AUTHENTICATION_REQUIRED or
     * IDENTITY_UNAVAILABLE. A refusal carries its code to the 401 entry point (AuthenticationFailures); a failure gets one
     * line per reason when it starts and once a minute while it lasts.
     */
    private static final com.lookahead.domain.Throttle THROTTLE=new com.lookahead.domain.Throttle();
    private static void unavailable(String code,RuntimeException failure){
        String types=failure==null?null:com.lookahead.domain.Events.types(failure),state=failure==null?null:com.lookahead.domain.Events.sqlState(failure);
        if(!THROTTLE.due("failed|"+code+"|"+types))return;
        var line=com.lookahead.domain.Events.failed(com.lookahead.domain.Events.EVENT,"sign-in.check",code);
        if(types!=null)line=line.addKeyValue("error.type",types);
        if(state!=null)line=line.addKeyValue("db.sqlState",state);
        line.log("API call answered 503: the sign-in check could not complete ({}{})",types==null?code:types,state==null?"":", SQL state "+state);
    }
}
