package com.lookahead.domain.job;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.signin.DevAccessCleanup;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.util.SignInRules;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DLV-969 cleanup on a store that only remembers what it was asked (DLV-1016: the job lists,
 * decides and logs). The conditional transactions themselves are proven against DynamoDB in
 * SignInStoreDatabaseTest.
 */
class DevAccessCleanupJobTest {
  static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

  record Row(UUID accountId, Instant requestedAt) implements DevAccessCleanup.Waiting {}

  /** {@code raced}: the fenced removal finds the account changed. */
  record Account(Row row, boolean exists, boolean admitted, boolean active, boolean raced)
      implements DevAccessCleanup.Candidate {}

  static final class Store implements DevAccessCleanup {
    final List<Account> accounts = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    RuntimeException failure;

    Row add(Duration age, boolean exists, boolean admitted, boolean active, boolean raced) {
      var row = new Row(UUID.randomUUID(), NOW.minus(age));
      accounts.add(new Account(row, exists, admitted, active, raced));
      return row;
    }

    @Override
    public Instant now() {
      return NOW;
    }

    @Override
    public Iterable<Waiting> waiting() {
      if (failure != null) throw failure;
      calls.add("waiting");
      return accounts.stream().<Waiting>map(Account::row).toList();
    }

    @Override
    public Candidate read(Waiting row) {
      calls.add("read " + row.accountId());
      return accounts.stream().filter(a -> a.row().equals(row)).findFirst().orElseThrow();
    }

    @Override
    public void dropRow(Waiting row) {
      calls.add("drop " + row.accountId());
    }

    @Override
    public boolean expire(Candidate candidate, Instant now) {
      var account = (Account) candidate;
      assertThat(now).isEqualTo(NOW);
      calls.add("expire " + account.row().accountId());
      return !account.raced();
    }
  }

  final Store store = new Store();
  final DevAccessCleanupJob cleanup = new DevAccessCleanupJob(store);
  static final Duration OLD = Duration.ofHours(48).plusSeconds(1);

  @Test
  void expiryIsSharedWithTheWaitingList() {
    assertThat(SignInRules.WAITING_EXPIRY.toHours()).isEqualTo(48);
  }

  @Test
  void onlyARequestOlderThanFortyEightHoursIsLookedAt() {
    store.add(Duration.ofHours(48), true, false, true, false); // exactly 48 h is not yet expired
    store.add(Duration.ofHours(1), true, false, true, false);
    Row old = store.add(OLD, true, false, true, false);
    assertThat(cleanup.expire()).isEqualTo(1);
    assertThat(store.calls)
        .containsExactly("waiting", "read " + old.accountId(), "expire " + old.accountId());
  }

  @Test
  void aRowLeftBehindIsDroppedAloneAndADisabledWaitingAccountIsKept() {
    Row gone = store.add(OLD, false, false, false, false),
        admitted = store.add(OLD, true, true, true, false),
        disabled = store.add(OLD, true, false, false, false);
    try (var log = LogCapture.start()) {
      assertThat(cleanup.run()).isZero();
      assertThat(store.calls)
          .containsExactly(
              "waiting",
              "read " + gone.accountId(),
              "drop " + gone.accountId(),
              "read " + admitted.accountId(),
              "drop " + admitted.accountId(),
              "read " + disabled.accountId());
      assertThat(log.lines("admission.expired")).isEmpty();
      assertThat(LogCapture.keys(log.only("admission.cleanup"))).containsEntry("count", 0);
    }
  }

  @Test
  void aLostRaceIsSkippedNotCountedAndNotRetried() {
    Row raced = store.add(OLD, true, false, true, true),
        next = store.add(OLD, true, false, true, false);
    try (var log = LogCapture.start()) {
      assertThat(cleanup.expire()).isEqualTo(1);
      assertThat(store.calls)
          .containsExactly(
              "waiting",
              "read " + raced.accountId(),
              "expire " + raced.accountId(),
              "read " + next.accountId(),
              "expire " + next.accountId());
      assertThat(log.only("admission.expired").getFormattedMessage())
          .contains(next.accountId().toString());
    }
  }

  @Test
  void oneLinePerRemovedAccountThenOneForTheRun() {
    Row first = store.add(OLD, true, false, true, false),
        second = store.add(Duration.ofDays(30), true, false, true, false);
    try (var log = LogCapture.start()) {
      assertThat(cleanup.run()).isEqualTo(2);
      var lines =
          log.lines().stream()
              .filter(line ->
                  line.getFormattedMessage().startsWith("Waiting account")
                      || line.getFormattedMessage().startsWith("Admission cleanup"))
              .toList();
      assertThat(lines)
          .extracting(line -> line.getFormattedMessage())
          .containsExactly(
              "Waiting account "
                  + first.accountId()
                  + " expired: not admitted within 48 h, removed with its sign-in identities",
              "Waiting account "
                  + second.accountId()
                  + " expired: not admitted within 48 h, removed with its sign-in identities",
              "Admission cleanup removed 2 expired waiting account(s)");
      assertThat(lines).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.INFO));
      assertThat(LogCapture.keys(lines.get(0)))
          .containsEntry("event.action", "admission.expired")
          .containsEntry("event.outcome", "success")
          .containsEntry("target.id", first.accountId());
      assertThat(LogCapture.keys(lines.get(2)))
          .containsEntry("event.action", "admission.cleanup")
          .containsEntry("event.outcome", "success")
          .containsEntry("count", 2);
    }
  }

  /**
   * DLV-969: failures are logged by type only (never the message, which may carry a secret) and the
   * next run tries again.
   */
  @Test
  void scheduledRunSwallowsFailuresSoTheSchedulerKeepsRunning() {
    store.failure = new IllegalStateException("password=synthetic-secret");
    try (var log = LogCapture.start()) {
      assertThat(cleanup.run()).isZero();
      var failed = log.only("admission.cleanup");
      assertThat(failed.getLevel()).isEqualTo(Level.WARN);
      assertThat(failed.getFormattedMessage())
          .isEqualTo(
              "Admission cleanup skipped this run: IllegalStateException; the next run tries"
                  + " again");
      assertThat(LogCapture.keys(failed))
          .containsEntry("reason", "UNEXPECTED")
          .containsEntry("error.type", "IllegalStateException");
      log.containsNone("synthetic-secret");
    }
    assertThatThrownBy(cleanup::expire).isInstanceOf(IllegalStateException.class);
  }
}
