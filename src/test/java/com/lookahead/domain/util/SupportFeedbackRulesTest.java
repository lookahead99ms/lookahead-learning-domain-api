package com.lookahead.domain.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** The support feedback rules that are plain functions. DLV-1016: the address test moved here from SupportEmailClient with its test. */
class SupportFeedbackRulesTest {
    @Test void anAddressIsOneWordAtOneWordWithADotAndAtMost254Characters() {
        assertThat(SupportFeedbackRules.email("a@b.co")).isTrue();
        for (String bad : new String[] {null, "", "a b@c.d", "a@b", "<a@b.co>", "x".repeat(250) + "@b.co"}) assertThat(SupportFeedbackRules.email(bad)).as(String.valueOf(bad)).isFalse();
    }
}
