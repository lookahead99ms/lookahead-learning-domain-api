package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** DLV-1016: the value checks that were written inside SignInService, with the status, code and message each one always answered. */
class SignInValueValidatorTest {
    static final String AGAIN = "Sign in again or contact the account administrator.";
    private static void refused(Runnable check, int status, String code, String message) {
        var failure = catchThrowableOfType(AccountFailure.class, check::run);
        assertThat(failure).as(code).isNotNull();
        assertThat(failure.status()).isEqualTo(status);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure).hasMessage(message);
    }

    @Test void aLabelADisplayNameAndAThemeAreCheckedAsBefore() {
        assertThatCode(() -> SignInValueValidator.label("  " + "x".repeat(80) + "  ")).doesNotThrowAnyException();
        for (String label : new String[] {null, "x".repeat(81), "tab\there", "<b>"})
            refused(() -> SignInValueValidator.label(label), 400, "INVALID_SIGN_IN_LABEL", AGAIN);
        assertThat(SignInValueValidator.displayName("  Ada Lovelace  ")).isEqualTo("Ada Lovelace");
        assertThat(SignInValueValidator.displayName("😀".repeat(160))).hasSize(320);
        for (String name : new String[] {"   ", "x".repeat(161), "new\nline", "\uD800"})
            refused(() -> SignInValueValidator.displayName(name), 422, "INVALID_PROFILE", AGAIN);
        for (String theme : new String[] {"light", "dark", "system"}) assertThatCode(() -> SignInValueValidator.theme(theme)).doesNotThrowAnyException();
        for (String theme : new String[] {null, "Light", "blue"}) refused(() -> SignInValueValidator.theme(theme), 422, "INVALID_PREFERENCES", AGAIN);
    }

    @Test void theFourLinksComeBackInTheirFixedOrderAndAWrongSetOfKindsIsRefused() {
        var sent = new LinkedHashMap<String, String>();
        sent.put("website", " https://example.org/me "); sent.put("x", null); sent.put("linkedin", ""); sent.put("github", "https://github.com/ada");
        Map<String, String> values = SignInValueValidator.links(sent);
        assertThat(values.keySet()).containsExactly("github", "linkedin", "x", "website");
        assertThat(values).containsEntry("github", "https://github.com/ada").containsEntry("linkedin", null).containsEntry("x", null).containsEntry("website", "https://example.org/me");
        refused(() -> SignInValueValidator.links(null), 422, "INVALID_PROFILE_LINK", "Provide the github, linkedin, x and website fields.");
        refused(() -> SignInValueValidator.links(Map.of("github", "https://github.com/ada")), 422, "INVALID_PROFILE_LINK", "Provide the github, linkedin, x and website fields.");
        sent.put("github", "https://example.org/ada");
        refused(() -> SignInValueValidator.links(sent), 422, "INVALID_PROFILE_LINK", "Use an https://github.com/… address for GitHub.");
    }

    @Test void aChallengeTokenHas43UrlSafeCharacters() {
        assertThatCode(() -> SignInValueValidator.challengeToken("A".repeat(41) + "_-")).doesNotThrowAnyException();
        for (String token : new String[] {null, "A".repeat(42), "A".repeat(44), "A".repeat(42) + "+"})
            refused(() -> SignInValueValidator.challengeToken(token), 400, "SIGN_IN_CHALLENGE_INVALID", AGAIN);
    }
}
