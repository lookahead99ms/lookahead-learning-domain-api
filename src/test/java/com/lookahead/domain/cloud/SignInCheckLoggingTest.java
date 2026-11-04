package com.lookahead.domain.cloud;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.LogCapture;
import com.lookahead.domain.security.IdentityUnavailableException;
import com.lookahead.learning.content.exception.AccountFailure;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 (owner, 2026-11-04): a refused or failed sign-in check carries its reason to the log (the 401 entry point
 * logs refusals by their code; failures are logged where they happen), never a token, claim or address.
 */
class SignInCheckLoggingTest {
    final CognitoFixture fixture = new CognitoFixture();
    final CognitoTokenVerifier verifier = new CognitoTokenVerifier(CognitoTokenVerifierTest.SETTINGS, fixture.client);

    Jwt token(Map<String, Object> overrides) {
        return Jwt.withTokenValue("synthetic-access-token").header("alg", "RS256").issuer(CognitoTokenVerifierTest.SETTINGS.issuer())
                .subject("synthetic-provider-subject").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("token_use", "access")
                .claim("client_id", CognitoTokenVerifierTest.SETTINGS.clientId()).claim("origin_jti", "synthetic-family").claim("auth_time", Instant.now())
                .claim("scope", "aws.cognito.signin.user.admin lookahead/account lookahead/content lookahead/support").claims(c -> c.putAll(overrides)).build();
    }

    private String refusal(Map<String, Object> overrides) {
        try {
            verifier.verify(token(overrides));
            throw new AssertionError("expected a refusal");
        } catch (OAuth2AuthenticationException refused) {
            assertThat(refused.getError().getErrorCode()).isEqualTo("invalid_token");
            return refused.getError().getDescription();
        }
    }

    @Test void eachClaimRefusalCarriesItsReasonCode() {
        assertThat(refusal(Map.of("aud", "synthetic-client"))).isEqualTo("AUDIENCE_PRESENT");
        assertThat(refusal(Map.of("iss", "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Other"))).isEqualTo("ISSUER_MISMATCH");
        assertThat(refusal(Map.of("token_use", "id"))).isEqualTo("NOT_ACCESS_TOKEN");
        assertThat(refusal(Map.of("client_id", "wrongclient123"))).isEqualTo("CLIENT_MISMATCH");
        assertThat(refusal(Map.of("scope", "openid"))).isEqualTo("SCOPES_MISSING");
        assertThat(refusal(Map.of("auth_time", Instant.now().plusSeconds(600)))).isEqualTo("TOKEN_TIMES_INVALID");
        assertThat(refusal(Map.of("origin_jti", ""))).isEqualTo("CLAIM_INVALID");
        fixture.response = request -> GetUserResponse.builder().username("learner")
                .userAttributes(AttributeType.builder().name("sub").value("another-subject").build()).build();
        assertThat(refusal(Map.of())).isEqualTo("SUBJECT_MISMATCH");
        assertThat(CognitoTokenVerifier.invalid().getError().getDescription()).isNull();
    }

    @Test void aMalformedTokenAndAnUnusableProviderAnswerAreLoggedOnceWithTheirTypes() {
        try (var log = LogCapture.start()) {
            var noIssuer = Jwt.withTokenValue("synthetic-access-token").header("alg", "RS256").subject("synthetic-provider-subject")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("token_use", "access").build();
            for (int i = 0; i < 2; i++) assertThatThrownBy(() -> verifier.verify(noIssuer)).isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                    refused -> assertThat(refused.getError().getDescription()).isEqualTo("TOKEN_MALFORMED"));
            fixture.response = request -> GetUserResponse.builder().username(null)
                    .userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();
            assertThatThrownBy(() -> verifier.verify(token(Map.of()))).isInstanceOf(OAuth2AuthenticationException.class);
            fixture.response = request -> { throw new IllegalStateException("provider said secret-thing"); };
            assertThatThrownBy(() -> verifier.verify(token(Map.of()))).isInstanceOf(IdentityUnavailableException.class);
            assertThat(log.lines("sign-in.check")).extracting(line -> line.getFormattedMessage()).containsExactly(
                    "Sign-in token check failed: the token's claims could not be checked; refused with 401 (NullPointerException)",
                    "Sign-in token check failed: Cognito's answer could not be used; answered 503 (IllegalStateException)");
            assertThat(log.lines("sign-in.check")).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
            log.containsNone("secret-thing", "synthetic-access-token");
        }
    }

    @Test void theApiConverterPassesRefusalCodesOnAndLogsFailuresOnce() {
        var registry = mock(CloudSignInRegistry.class);
        var request = new MockHttpServletRequest();
        request.addHeader(CloudDomainTokenConverter.PROOF_HEADER, "p".repeat(43));
        var converter = new CloudDomainTokenConverter(verifier, registry, request);
        fixture.response = r -> GetUserResponse.builder().username("learner")
                .userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();
        var api = Jwt.withTokenValue("synthetic-access-token").header("alg", "RS256").issuer(CognitoTokenVerifierTest.SETTINGS.issuer())
                .subject("synthetic-provider-subject").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("token_use", "access")
                .claim("client_id", CognitoTokenVerifierTest.SETTINGS.clientId()).claim("origin_jti", "synthetic-family").claim("auth_time", Instant.now())
                .claim("scope", CognitoTokenVerifier.ADMIN_SCOPE).build();
        try (var log = LogCapture.start()) {
            when(registry.authenticate(any(), anyString())).thenThrow(new AccountFailure(401, "SIGN_IN_REVOKED", "Sign in again."));
            assertThatThrownBy(() -> converter.convert(api)).isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                    refused -> assertThat(refused.getError().getDescription()).isEqualTo("SIGN_IN_REVOKED"));
            reset(registry);
            when(registry.authenticate(any(), anyString())).thenThrow(new AccountFailure(503, "ACCOUNT_STORAGE_UNAVAILABLE", "Retry."));
            assertThatThrownBy(() -> converter.convert(api)).isInstanceOf(IdentityUnavailableException.class);
            reset(registry);
            when(registry.authenticate(any(), anyString())).thenThrow(new DataAccessResourceFailureException("db.internal password=hunter2",
                    new SQLException("refused", "08006")));
            assertThatThrownBy(() -> converter.convert(api)).isInstanceOf(IdentityUnavailableException.class);
            assertThatThrownBy(() -> converter.convert(api)).isInstanceOf(IdentityUnavailableException.class);
            assertThat(log.lines("sign-in.check")).extracting(line -> line.getFormattedMessage()).containsExactly(
                    "API call answered 503: the sign-in check could not complete (ACCOUNT_STORAGE_UNAVAILABLE)",
                    "API call answered 503: the sign-in check could not complete (DataAccessResourceFailureException < SQLException, SQL state 08006)");
            assertThat(LogCapture.keys(log.lines("sign-in.check").getLast())).containsEntry("reason", "UNEXPECTED").containsEntry("db.sqlState", "08006");
            log.containsNone("hunter2", "db.internal", "synthetic-access-token");
        }
    }

    @Test void anUnreadablePlanCardOnTheMergePageAndACleanupDatabaseFailureAreExplained() {
        try (var log = LogCapture.start()) {
            assertThat(CloudMergeService.percent("{not json", "{}")).isZero();
            assertThat(CloudMergeService.percent(null, "{}")).isZero();
            var line = log.only("merge.candidates");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).startsWith("A study plan's progress could not be read for the merge page (").endsWith("); it shows 0%");
            var jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                    .thenThrow(new DataAccessResourceFailureException("password=hunter2", new SQLException("down", "57P01")));
            var manager = mock(PlatformTransactionManager.class);
            new CloudAdmissionCleanup(jdbc, manager).run();
            assertThat(log.only("admission.cleanup").getFormattedMessage())
                    .isEqualTo("Admission cleanup skipped this hour: DataAccessResourceFailureException < SQLException (SQL state 57P01); the next run tries again");
            log.containsNone("hunter2");
        }
    }
}
