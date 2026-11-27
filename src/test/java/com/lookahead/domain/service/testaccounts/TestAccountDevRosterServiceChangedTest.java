package com.lookahead.domain.service.testaccounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookahead.domain.db.store.testaccounts.TestAccountRegistryRows.RosterFacts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DLV-1016: "changed since its last reset" was decided in the store; it is the roster service's rule over the store's facts. */
class TestAccountDevRosterServiceChangedTest {
    private static final Instant RESET = Instant.parse("2026-11-20T10:00:00Z");
    private static RosterFacts facts(Instant reset, Instant changed, Instant written) { return new RosterFacts(UUID.randomUUID(), "active", true, reset, changed, written); }

    @Test void anAccountThatWasNeverResetCountsAsChanged() {
        assertThat(TestAccountDevRosterService.changed(facts(null, RESET.minusSeconds(60), null))).isTrue();
    }
    @Test void aRegistryChangeOrAnyWriteAfterTheResetCountsAsChanged() {
        assertThat(TestAccountDevRosterService.changed(facts(RESET, RESET.plusSeconds(1), null))).isTrue();
        assertThat(TestAccountDevRosterService.changed(facts(RESET, RESET.minusSeconds(1), RESET.plusSeconds(1)))).isTrue();
    }
    @Test void nothingAfterTheResetIsUnchangedAndTheResetInstantItselfDoesNotCount() {
        assertThat(TestAccountDevRosterService.changed(facts(RESET, RESET, null))).isFalse();
        assertThat(TestAccountDevRosterService.changed(facts(RESET, RESET.minusSeconds(1), RESET))).isFalse();
        assertThat(TestAccountDevRosterService.changed(facts(RESET, RESET.minusSeconds(1), RESET.minusSeconds(1)))).isFalse();
    }
}
