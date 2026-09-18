package com.lookahead.domain.security;

import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainTokenConverterTest {
    private final UUID subject = UUID.randomUUID();
    private final IdentitySettings settings = IdentitySettings.from(IdentitySettingsTest.local());
    private final AccountRepository accounts = mock(AccountRepository.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final IdentityVerificationClient identity = mock(IdentityVerificationClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final DomainTokenConverter converter = new DomainTokenConverter(settings, identity, accounts);

    @Test void onlyVerifiedIdentityProvisionsADomainSubjectAndNoGrants() {
        when(identity.verify("synthetic-token")).thenReturn(active(subject.toString(), settings.gatewayClientId()));
        var authenticated = converter.convert(token(subject.toString(), settings.gatewayClientId(), "lookahead-api"));
        assertThat(authenticated.getPrincipal()).isEqualTo(new AccountPrincipal(subject, "learner", "Learner", true));
        assertThat(authenticated.getAuthorities()).extracting("authority").containsExactly("SCOPE_account", "SCOPE_content");
        verify(identity).verify("synthetic-token");
        verify(accounts).ensureSubject(subject);
        verifyNoMoreInteractions(accounts);
    }
    @Test void wrongAudienceClientOrMalformedSubjectCannotReachIdentityOrDatabase() {
        for (Jwt token : List.of(token(subject.toString(), "other-client", "lookahead-api"),
                token(subject.toString(), settings.gatewayClientId(), "other-api"),
                token("not-an-owner", settings.gatewayClientId(), "lookahead-api"),
                token("1-1-1-1-1", settings.gatewayClientId(), "lookahead-api")))
            assertThatThrownBy(() -> converter.convert(token)).isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(identity, accounts);
    }
    @Test void revocationOrDisabledIdentityIsDeniedWithoutOwnerProvisioning() {
        when(identity.verify(anyString())).thenReturn(new IdentityVerificationClient.Verification(false, null, null, null, null));
        assertThatThrownBy(() -> converter.convert(valid())).isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(accounts);
    }
    @Test void identityIsCheckedAgainOnEveryRequest() {
        when(identity.verify(anyString())).thenReturn(active(subject.toString(), settings.gatewayClientId()),
                new IdentityVerificationClient.Verification(false, null, null, null, null));
        converter.convert(valid());
        assertThatThrownBy(() -> converter.convert(valid())).isInstanceOf(OAuth2AuthenticationException.class);
        verify(identity, times(2)).verify("synthetic-token");
        verify(accounts, times(1)).ensureSubject(subject);
    }
    @Test void mismatchedSubjectClientOrUnavailableAuthorityFailsClosed() {
        for (var response : List.of(active(UUID.randomUUID().toString(), settings.gatewayClientId()), active(subject.toString(), "other-client"))) {
            when(identity.verify(anyString())).thenReturn(response);
            assertThatThrownBy(() -> converter.convert(valid())).isInstanceOf(IdentityUnavailableException.class);
        }
        when(identity.verify(anyString())).thenThrow(new IdentityUnavailableException());
        assertThatThrownBy(() -> converter.convert(valid())).isInstanceOf(IdentityUnavailableException.class);
        verifyNoInteractions(accounts);
    }
    private IdentityVerificationClient.Verification active(String owner, String client) {
        return new IdentityVerificationClient.Verification(true, owner, "learner", "Learner", client);
    }
    private Jwt valid() { return token(subject.toString(), settings.gatewayClientId(), "lookahead-api"); }
    private Jwt token(String owner, String client, String audience) {
        return Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject(owner).issuer(settings.issuer())
                .audience(List.of(audience)).claim("client_id", client).claim("scope", "account content")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
