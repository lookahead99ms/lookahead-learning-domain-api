package com.lookahead.domain.service.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.plan.StudyPlanFreeLedger;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

/**
 * The free allowance rules on a mocked ledger; the DynamoDB ledger itself is proven by
 * StudyPlanFreeLedgerStoreDatabaseTest.
 */
class StudyPlanFreeAllowanceServiceTest {
  private final UUID owner = UUID.randomUUID();
  private final StudyPlanFreeLedger ledger =
      mock(StudyPlanFreeLedger.class, withSettings().mockMaker(MockMakers.SUBCLASS));
  private final AccountGrantService accounts = mock(AccountGrantService.class);
  private final ProtectedContentManifestService publication =
      mock(ProtectedContentManifestService.class);
  private final StudyPlanFreeAllowanceService allowance =
      new StudyPlanFreeAllowanceService(
          ledger,
          accounts,
          publication,
          Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC));

  private record Row(UUID id, String state, LocalDate startDate, long dayCount, UUID currentVersion)
      implements StudyPlanFreeLedger.PlanRow {}

  private Row row(String state, String start, Set<String> content) {
    var row =
        new Row(
            UUID.randomUUID(),
            state,
            start == null ? null : LocalDate.parse(start),
            7,
            UUID.randomUUID());
    when(ledger.contentIds(owner, row.id(), row.currentVersion())).thenReturn(content);
    return row;
  }

  /**
   * {@code count} plans the store answers, each active, in date and on free content, so each
   * counts.
   */
  private void activeFreePlans(int count) {
    var rows = new ArrayList<StudyPlanFreeLedger.PlanRow>();
    for (int n = 0; n < count; n++) rows.add(row("active", "2026-10-10", Set.of("lesson-free")));
    when(ledger.plans(owner)).thenReturn(rows);
  }

  /**
   * DLV-1016: the rule the ledger store applied, now the service's: archived, completed, expired,
   * excluded and paid plans do not count.
   */
  @Test
  void activeFreePlansSkipArchivedCompletedExpiredExcludedAndPaidPlans() {
    var fresh = row("active", "2026-10-10", Set.of("lesson-free"));
    var archived = row("archived", "2026-10-10", Set.of("lesson-free"));
    var completed = row("completed", "2026-10-10", Set.of("lesson-free"));
    var expired = row("active", "2026-01-01", Set.of("lesson-free"));
    var lastDay =
        row(
            "active",
            "2026-07-12",
            Set.of("lesson-free")); // 7 days + 90 = 2026-10-17: still counts on 2026-10-16
    var gone =
        row(
            "active",
            "2026-07-11",
            Set.of("lesson-free")); // archive date is today: no longer counts
    var paid = row("active", "2026-10-10", Set.of("lesson-paid"));
    var undated = row(null, null, Set.of("lesson-free"));
    when(ledger.plans(owner))
        .thenReturn(List.of(fresh, archived, completed, expired, lastDay, gone, paid, undated));
    // Three count (fresh, lastDay, undated): the limit of two is reached.
    assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
        .isInstanceOfSatisfying(
            AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
    verify(ledger, never()).contentIds(owner, archived.id(), archived.currentVersion());
    verify(ledger, never()).contentIds(owner, completed.id(), completed.currentVersion());
    verify(ledger, never()).contentIds(owner, expired.id(), expired.currentVersion());
    verify(ledger, never()).contentIds(owner, gone.id(), gone.currentVersion());
    verify(ledger).contentIds(owner, paid.id(), paid.currentVersion());
    // The plan being activated is left out: one other plan counts, which is below the limit of two.
    clearInvocations(ledger);
    when(ledger.plans(owner)).thenReturn(List.of(fresh, undated, paid));
    allowance.beforeActivation(owner, fresh.id(), Set.of("lesson-free"));
    verify(ledger, never()).contentIds(owner, fresh.id(), fresh.currentVersion());
  }

  @BeforeEach
  void freeAccount() {
    when(accounts.findTopicGrants(owner)).thenReturn(Set.of());
    when(publication.freeContentIds()).thenReturn(Set.of("lesson-free"));
    activeFreePlans(0);
    when(ledger.creations(owner)).thenReturn(0);
  }

  /**
   * DLV-968: staff:documents is not course access; a course grant or purchased topic still ends the
   * free allowance.
   */
  @Test
  void staffDocumentsScopeKeepsAnAccountFreeButAnyCourseGrantDoesNot() {
    when(accounts.findTopicGrants(owner))
        .thenReturn(Set.of("staff:documents", "content:lesson-free"));
    assertThat(allowance.freeAccount(owner)).isTrue();
    assertThat(allowance.beforeCreate(owner, Set.of("lesson-free"))).isTrue();
    when(accounts.findTopicGrants(owner)).thenReturn(Set.of("staff:documents", "learn:core-java"));
    assertThat(allowance.freeAccount(owner)).isFalse();
    assertThat(allowance.beforeCreate(owner, Set.of("lesson-paid"))).isFalse();
  }

  @Test
  void freeCreationRequiresFreeContentAndTwoAvailableActiveSlots() {
    assertThat(allowance.beforeCreate(owner, Set.of("lesson-free"))).isTrue();
    assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-paid")))
        .isInstanceOfSatisfying(
            AccountFailure.class, e -> assertThat(e.code()).isEqualTo("COURSE_ACCESS_REQUIRED"));
    activeFreePlans(2);
    assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
        .isInstanceOfSatisfying(
            AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
    assertThatThrownBy(
            () -> allowance.beforeActivation(owner, UUID.randomUUID(), Set.of("lesson-free")))
        .isInstanceOfSatisfying(
            AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
  }

  @Test
  void lifetimeLimitDoesNotUsePlanNumberAndCountsSuccessfulImportViaSameCreatePath() {
    when(ledger.creations(owner)).thenReturn(15);
    assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
        .isInstanceOfSatisfying(
            AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_LIFETIME_PLAN_LIMIT"));
    verify(ledger, never()).recordCreation(any(), any());
    allowance.afterCreate(owner, UUID.randomUUID(), true);
    verify(ledger).recordCreation(eq(owner), any(UUID.class));
  }

  @Test
  void freeAccountHasThreeSuccessfulDeletionsPerRollingWindow() {
    when(ledger.deletionsSince(eq(owner), any())).thenReturn(2, 3);
    when(ledger.oldestDeletionSince(eq(owner), any()))
        .thenReturn(Instant.parse("2026-10-01T12:00:00Z"));
    allowance.beforeDelete(owner);
    assertThatThrownBy(() -> allowance.beforeDelete(owner))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            e -> {
              assertThat(e.code()).isEqualTo("FREE_DELETE_LIMIT");
              assertThat(e.getMessage()).contains("2026-10-22T12:00Z");
            });
    verify(ledger, never()).recordDeletion(any(), any());
    allowance.afterDelete(owner, UUID.randomUUID(), true);
    verify(ledger).recordDeletion(eq(owner), any(UUID.class));
  }

  @Test
  void convertingPaidScopeToFreeScopeSpendsLifetimeAllowanceOnce() {
    UUID planId = UUID.randomUUID();
    when(ledger.creationRecorded(owner, planId)).thenReturn(false, true);
    assertThat(
            allowance.beforeFreeScopeChange(
                owner, planId, Set.of("lesson-paid"), Set.of("lesson-free"), true))
        .isTrue();
    allowance.afterCreate(owner, planId, true);
    assertThat(
            allowance.beforeFreeScopeChange(
                owner, planId, Set.of("lesson-paid"), Set.of("lesson-free"), true))
        .isFalse();
    verify(ledger).recordCreation(owner, planId);
  }

  @Test
  void paidAccountIsNotSubjectToFreeAllowance() {
    when(accounts.findTopicGrants(owner)).thenReturn(Set.of("learn:core-java"));
    assertThat(allowance.beforeCreate(owner, Set.of("lesson-paid"))).isFalse();
    allowance.beforeDelete(owner);
    verify(ledger, never()).creations(any());
    verify(ledger, never()).deletionsSince(any(), any());
  }

  /**
   * DLV-969: each free allowance hit is one lookahead.limit WARN line with the limit, its size and
   * its window.
   */
  @Test
  void eachFreeAllowanceHitIsOneLimitLine() {
    try (var log = LogCapture.start()) {
      activeFreePlans(2);
      assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
          .isInstanceOf(AccountFailure.class);
      activeFreePlans(0);
      when(ledger.creations(owner)).thenReturn(15);
      assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
          .isInstanceOf(AccountFailure.class);
      when(ledger.deletionsSince(eq(owner), any())).thenReturn(3);
      when(ledger.oldestDeletionSince(eq(owner), any()))
          .thenReturn(Instant.parse("2026-10-01T12:00:00Z"));
      assertThatThrownBy(() -> allowance.beforeDelete(owner)).isInstanceOf(AccountFailure.class);

      assertThat(log.lines())
          .allSatisfy(line -> {
            assertThat(line.getLoggerName()).isEqualTo("lookahead.limit");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(LogCapture.keys(line))
                .containsEntry("account.id", owner)
                .containsEntry("event.outcome", "refused");
          });
      assertThat(log.lines())
          .extracting(line -> LogCapture.keys(line).get("reason"))
          .containsExactly(
              "FREE_ACTIVE_PLAN_LIMIT", "FREE_LIFETIME_PLAN_LIMIT", "FREE_DELETE_LIMIT");
      assertThat(LogCapture.keys(log.lines().get(2)))
          .containsEntry("limit.name", "free-plan-deletions")
          .containsEntry("limit.max", 3L)
          .containsEntry("limit.window", "21d");
      assertThat(log.messages())
          .containsExactly(
              "Free plan limit hit for account " + owner + ": at most 2 active free plans at once",
              "Free plan limit hit for account "
                  + owner
                  + ": all 15 free plan creations of its lifetime are used",
              "Free plan limit hit for account "
                  + owner
                  + ": at most 3 deletions in each rolling 21 days; next deletion allowed"
                  + " 2026-10-22T12:00Z");
    }
  }
}
