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
        } catch(AccountFailure denied){if(denied.status()>=500)throw new IdentityUnavailableException();throw CognitoTokenVerifier.invalid();}
        catch(RuntimeException unavailable){throw new IdentityUnavailableException();}
    }
}
