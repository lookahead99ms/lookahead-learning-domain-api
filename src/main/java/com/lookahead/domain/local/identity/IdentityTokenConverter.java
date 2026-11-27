package com.lookahead.domain.local.identity;

import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.util.CanonicalUuid;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Signature/issuer/time run first; this adds client, persisted-token and current-account checks. */
public final class IdentityTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final IdentityConnection connection;
    private final IdentityVerificationClient identity;
    private final AccountGrantService accounts;
    public IdentityTokenConverter(IdentityConnection connection, IdentityVerificationClient identity, AccountGrantService accounts) {
        this.connection = connection; this.identity = identity; this.accounts = accounts;
    }
    @Override public AbstractAuthenticationToken convert(Jwt token) {
        if (token.getTokenValue().length() > 16 * 1024 || token.getAudience() == null
                || !token.getAudience().contains("lookahead-api") || token.getSubject() == null
                || !connection.bffClientId().equals(token.getClaimAsString("client_id"))) throw invalid();
        UUID subject = CanonicalUuid.parse(token.getSubject());
        if (subject == null) throw invalid();
        var verified = identity.verify(token.getTokenValue());
        if (!verified.active()) throw invalid();
        if (!token.getSubject().equals(verified.subject()) || !connection.bffClientId().equals(verified.clientId())) throw new SignInCheckUnavailableException();
        accounts.ensureSubject(subject);
        var principal = new AccountPrincipal(subject, verified.username(), verified.displayName(), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, new JwtGrantedAuthoritiesConverter().convert(token));
    }
    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "Invalid access token");
    }
}
