package com.lookahead.domain.security.token;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.exception.AccountFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;

public final class SignInTokenConverter implements Converter<Jwt,AbstractAuthenticationToken> {
    public static final String PROOF_HEADER="X-LookAhead-SignIn-Proof";
    private final SignInTokenVerifier verifier;
    private final SignInService signInService;
    private final HttpServletRequest request;
    public SignInTokenConverter(SignInTokenVerifier verifier,SignInService signInService,HttpServletRequest request){this.verifier=verifier;this.signInService=signInService;this.request=request;}
    @Override public AbstractAuthenticationToken convert(Jwt token){
        String proof=request.getHeader(PROOF_HEADER);
        // DLV-969 decision B: an API sign-in token is admissible only alongside the Gateway BFF's per-sign-in proof,
        // which SignInService.authenticate then binds to this exact sign-in (a forged or stale proof is refused there).
        var identity=verifier.verifyForApiCall(token,proof!=null && !proof.isBlank() && proof.length()<=512);
        try {
            var principal=signInService.authenticate(identity,proof);
            return UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("SCOPE_account"),new SimpleGrantedAuthority("SCOPE_content"),new SimpleGrantedAuthority("SCOPE_support")));
        } catch(AccountFailure denied){
            if(denied.status()>=500){unavailable(denied.code(),null);throw new SignInCheckUnavailableException();}
            throw SignInTokenVerifier.invalid(EventLog.safe(denied.code()));
        }
        catch(RuntimeException unavailable){unavailable("UNEXPECTED",unavailable);throw new SignInCheckUnavailableException();}
    }

    /*
     * DLV-969 (owner, 2026-11-04): the 401 or 503 of an API call used to say only AUTHENTICATION_REQUIRED or
     * IDENTITY_UNAVAILABLE. A refusal carries its code to the 401 entry point (EventLog.authenticationRefused); a failure gets one
     * line per reason when it starts and once a minute while it lasts.
     */
    private static final LogThrottle THROTTLE=new LogThrottle();
    private static void unavailable(String code,RuntimeException failure){
        String types=failure==null?null:EventLog.types(failure);
        if(!THROTTLE.due("failed|"+code+"|"+types))return;
        var line=EventLog.failed(EventLog.EVENT,"sign-in.check",code);
        if(types!=null)line=line.addKeyValue("error.type",types);
        line.log("API call answered 503: the sign-in check could not complete ({})",types==null?code:types);
    }
}
