package com.lookahead.platform.security;

import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Signature/issuer/time run first; this adds client, persisted-token and current-account checks. */
public final class PlatformTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final IdentitySettings settings;
    private final IdentityVerificationClient identity;
    private final AccountRepository accounts;
    public PlatformTokenConverter(IdentitySettings settings, IdentityVerificationClient identity, AccountRepository accounts) {
        this.settings = settings; this.identity = identity; this.accounts = accounts;
    }
    @Override public AbstractAuthenticationToken convert(Jwt token) {
        if (token.getTokenValue().length() > 16 * 1024 || token.getAudience() == null
                || !token.getAudience().contains("lookahead-api") || token.getSubject() == null
                || !settings.gatewayClientId().equals(token.getClaimAsString("client_id"))) throw invalid();
        UUID subject;
        try {
            subject = UUID.fromString(token.getSubject());
            if (!subject.toString().equals(token.getSubject())) throw invalid();
        } catch (IllegalArgumentException error) { throw invalid(); }
        var verified = identity.verify(token.getTokenValue());
        if (!verified.active()) throw invalid();
        if (!token.getSubject().equals(verified.subject()) || !settings.gatewayClientId().equals(verified.clientId())) throw new IdentityUnavailableException();
        accounts.ensureSubject(subject);
        var principal = new AccountPrincipal(subject, verified.username(), verified.displayName(), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, new JwtGrantedAuthoritiesConverter().convert(token));
    }
    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "Invalid access token");
    }
}
