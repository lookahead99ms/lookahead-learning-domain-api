package com.lookahead.domain.access;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.LogCapture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969: the role-label number loop logs each clash, the try that gave the number, and giving up. Database-free. */
class StaffAliasesRetryTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    // Interface mock: the subclass maker, as in the other suites.
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final StaffAliases aliases = new StaffAliases(jdbc, mock(StaffAccessService.class), mock(StaffAccessRepository.class), manager);
    final UUID account = UUID.randomUUID();

    @Test void aClashIsRetriedAndTheNumberGivenOnTheNextTryIsLogged() {
        when(jdbc.queryForList(contains("FROM staff_aliases"), eq(Integer.class), eq(account), eq("admin")))
                .thenReturn(List.of(), List.of(), List.of(3));
        when(jdbc.update(contains("INSERT INTO staff_aliases"), eq(account), eq("admin"), eq("admin")))
                .thenThrow(new DuplicateKeyException("taken")).thenReturn(1);
        try (var log = LogCapture.start()) {
            assertThat(aliases.number(account, "admin")).isEqualTo(3);
            var retry = log.only("alias.assign");
            assertThat(retry.getLevel()).isEqualTo(Level.WARN);
            assertThat(retry.getFormattedMessage()).isEqualTo("Role label number for account " + account
                    + " (Admin) was taken by someone else at the same moment: attempt 1 of 5; trying the next number");
            assertThat(LogCapture.keys(retry)).containsEntry("retry.attempt", 1).containsEntry("retry.max", 5).containsEntry("reason", "NUMBER_TAKEN");
            var given = log.only("alias.assigned");
            assertThat(given.getLevel()).isEqualTo(Level.INFO);
            assertThat(given.getFormattedMessage()).isEqualTo("Role label Admin 3 given to account " + account + " on attempt 2 of 5");
        }
    }

    @Test void aLabelThatAlreadyExistsIsReadQuietly() {
        when(jdbc.queryForList(contains("FROM staff_aliases"), eq(Integer.class), eq(account), eq("learner"))).thenReturn(List.of(7));
        try (var log = LogCapture.start()) {
            assertThat(aliases.number(account, "learner")).isEqualTo(7);
            assertThat(log.lines()).isEmpty();
        }
    }

    @Test void givingUpAfterFiveClashesIsOneWarningNotAnError() {
        when(jdbc.queryForList(contains("FROM staff_aliases"), eq(Integer.class), eq(account), eq("tester"))).thenReturn(List.of());
        when(jdbc.update(contains("INSERT INTO staff_aliases"), eq(account), eq("tester"), eq("tester"))).thenThrow(new DuplicateKeyException("taken"));
        try (var log = LogCapture.start()) {
            assertThatIllegalStateException().isThrownBy(() -> aliases.number(account, "tester"));
            assertThat(log.lines("alias.assign")).hasSize(6).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
            assertThat(log.lines().getLast().getFormattedMessage()).isEqualTo("Could not give a role label to account " + account + " (Tester) after 5 attempts");
            assertThat(LogCapture.keys(log.lines().getLast())).containsEntry("reason", "RETRIES_EXHAUSTED").containsEntry("event.outcome", "failure");
        }
    }
}
