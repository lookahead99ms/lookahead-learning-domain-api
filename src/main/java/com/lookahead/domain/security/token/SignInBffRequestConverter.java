package com.lookahead.domain.security.token;

import com.lookahead.domain.security.config.SignInBffSecret;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converts the decoded token of a request on the internal sign-in routes into its authentication. Two checks come
 * first: the request must carry the Gateway BFF's shared secret, compared in constant time for every request; then
 * {@link SignInTokenVerifier} verifies the token. Created by the sign-in security configuration for its filter chain.
 */
public final class SignInBffRequestConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final SignInTokenVerifier verifier;
    private final SignInBffSecret bffSecret;
    private final HttpServletRequest request;
    public SignInBffRequestConverter(SignInTokenVerifier verifier, SignInBffSecret bffSecret, HttpServletRequest request) {
        this.verifier = verifier; this.bffSecret = bffSecret; this.request = request;
    }
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
                String secret=request.getHeader(SignInBffSecret.HEADER);
                // Compare every request, including absent credentials, before any
                // authentication can be returned. No header controls whether the
                // constant-time credential check executes.
                byte[] supplied=Objects.toString(secret,"").getBytes(StandardCharsets.UTF_8);
                boolean bffVerified=MessageDigest.isEqual(supplied,bffSecret.value().getBytes(StandardCharsets.UTF_8));
                if(!bffVerified)throw SignInTokenVerifier.invalid("GATEWAY_SECRET_MISMATCH");
                return UsernamePasswordAuthenticationToken.authenticated(verifier.verify(jwt,true),null,List.of());
    }
}
