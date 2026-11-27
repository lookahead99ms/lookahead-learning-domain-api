package com.lookahead.domain.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalUuidTest {
    @Test void aUuidInItsCanonicalFormIsRead() {
        assertThat(CanonicalUuid.parse("11111111-2222-3333-4444-55555555abcd"))
                .isEqualTo(UUID.fromString("11111111-2222-3333-4444-55555555abcd"));
    }

    @Test void anAbsentMalformedOrDifferentlyWrittenUuidIsNoUuid() {
        assertThat(CanonicalUuid.parse(null)).isNull();
        for (String text : List.of("", "not-a-uuid", "11111111-2222-3333-4444-55555555ABCD", "1-1-1-1-1",
                " 11111111-2222-3333-4444-55555555abcd", "11111111222233334444555555555555"))
            assertThat(CanonicalUuid.parse(text)).as(text).isNull();
    }
}
