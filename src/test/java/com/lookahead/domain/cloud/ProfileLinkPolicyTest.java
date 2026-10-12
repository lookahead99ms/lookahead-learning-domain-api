package com.lookahead.domain.cloud;

import com.lookahead.learning.content.exception.AccountFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProfileLinkPolicyTest {
    @ParameterizedTest
    @CsvSource({
            "github, https://github.com/avery",
            "github, https://www.github.com/avery/repo?tab=readme#top",
            "github, HTTPS://GitHub.com:443/avery",
            "linkedin, https://www.linkedin.com/in/avery-learner",
            "linkedin, https://linkedin.com/in/avery",
            "x, https://x.com/avery",
            "x, https://www.x.com/avery",
            "x, https://twitter.com/avery",
            "x, https://www.twitter.com/avery",
            "website, https://avery.example.dev",
            "website, https://example.com/portfolio/",
            "website, https://sub.example.co.uk:443/a?b=c"})
    void acceptsHttpsLinksForTheirNetworkAndStoresThemUnchanged(String kind, String link) {
        assertThat(ProfileLinkPolicy.validate(kind, link)).isEqualTo(link);
        assertThat(ProfileLinkPolicy.validate(kind, "  " + link + "\n")).isEqualTo(link);
    }

    @Test void nullOrBlankClearsTheLink() {
        for (String kind : ProfileLinkPolicy.KINDS) {
            assertThat(ProfileLinkPolicy.validate(kind, null)).isNull();
            assertThat(ProfileLinkPolicy.validate(kind, "")).isNull();
            assertThat(ProfileLinkPolicy.validate(kind, " \t\n ")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/avery", "ftp://github.com/avery", "github.com/avery", "//github.com/avery",
            "https:github.com/avery", "https:///avery", "https://github.com", "https://github.com/", "https://github.com//",
            "https://user:pass@github.com/avery", "https://user@github.com/avery", "https://github.com:8443/avery",
            "https://github.com:80/avery", "https://gist.github.com/avery", "https://github.com.evil.test/avery",
            "https://evilgithub.com/avery", "https://github.com/a b", "https://github.com/a\u0000b",
            "https://github.com/a b", "https://github.com/a b", "https://github.com/%zz",
            "https://github.com/a\ud800b", "javascript:alert(1)", "https://github.com./avery"})
    void rejectsUnsafeOrForeignGithubLinksNamingTheField(String link) {
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("github", link))
                .isInstanceOfSatisfying(AccountFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("INVALID_PROFILE_LINK");
                    assertThat(failure.getMessage()).isEqualTo("Use an https://github.com/… address for GitHub.");
                });
    }

    @Test void eachNetworkAcceptsOnlyItsOwnHostsAndNamesItsField() {
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("linkedin", "https://github.com/avery"))
                .hasMessage("Use an https://linkedin.com/… address for LinkedIn.");
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("linkedin", "https://www.linkedin.com/"))
                .hasMessage("Use an https://linkedin.com/… address for LinkedIn.");
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("x", "https://mobile.twitter.com/avery"))
                .hasMessage("Use an https://x.com/… address for X.");
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("x", "https://x.com"))
                .hasMessage("Use an https://x.com/… address for X.");
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("website", "http://example.com"))
                .hasMessage("Use an https:// address for your website.");
        assertThatIllegalArgumentException().isThrownBy(() -> ProfileLinkPolicy.validate("mastodon", "https://example.com/@a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost", "https://localhost./a", "https://app.localhost", "https://intranet",
            "https://127.0.0.1", "https://10.0.0.1/admin", "https://1.2.3", "https://0x7f.1",
            "https://[::1]/", "https://[2001:db8::1]/", "https://example.com:8080/", "https://me@example.com",
            "https://example.com/a b", "http://example.com"})
    void websiteRejectsLocalNumericAndCredentialedHosts(String link) {
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("website", link))
                .isInstanceOfSatisfying(AccountFailure.class, failure -> assertThat(failure.code()).isEqualTo("INVALID_PROFILE_LINK"));
    }

    @Test void lengthCountsUnicodeCodePointsAfterStripping() {
        String prefix = "https://example.com/";
        String atLimit = prefix + "é".repeat(300 - prefix.length());
        assertThat(ProfileLinkPolicy.validate("website", "  " + atLimit + "  ")).isEqualTo(atLimit);
        assertThatThrownBy(() -> ProfileLinkPolicy.validate("website", atLimit + "a"))
                .isInstanceOf(AccountFailure.class);
        String emoji = prefix + "😀".repeat(300 - prefix.length());
        assertThat(ProfileLinkPolicy.validate("website", emoji)).isEqualTo(emoji);
    }

    @Test void wrongShapeMessageListsEveryField() {
        var failure = ProfileLinkPolicy.invalidShape();
        assertThat(failure.status()).isEqualTo(422);
        assertThat(failure.code()).isEqualTo("INVALID_PROFILE_LINK");
        assertThat(failure.getMessage()).isEqualTo("Provide the github, linkedin, x and website fields.");
    }
}
