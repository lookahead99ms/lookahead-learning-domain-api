package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.util.PlanJson;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The free allowance rules on a mocked ledger; the DynamoDB ledger itself is proven by DynamoFreePlanLedgerStoreDatabaseTest. */
class FreePlanAllowanceTest {
    private final UUID owner = UUID.randomUUID();
    private final FreePlanLedgerStore ledger = mock(FreePlanLedgerStore.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ProtectedContentPolicy publication = mock(ProtectedContentPolicy.class);
    private final FreePlanAllowance allowance = new FreePlanAllowance(ledger, accounts, publication,
            new PlanJson(new JsonMapper()), Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach void freeAccount() {
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of());
        when(publication.freeContentIds()).thenReturn(Set.of("lesson-free"));
        when(ledger.activeFreePlans(eq(owner), any(), any(), any())).thenReturn(0);
        when(ledger.creations(owner)).thenReturn(0);
    }

    /** DLV-968: staff:documents is not course access; a course grant or purchased topic still ends the free allowance. */
    @Test void staffDocumentsScopeKeepsAnAccountFreeButAnyCourseGrantDoesNot() {
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of("staff:documents", "content:lesson-free"));
        assertThat(allowance.freeAccount(owner)).isTrue();
        assertThat(allowance.beforeCreate(owner, Set.of("lesson-free"))).isTrue();
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of("staff:documents", "learn:core-java"));
        assertThat(allowance.freeAccount(owner)).isFalse();
        assertThat(allowance.beforeCreate(owner, Set.of("lesson-paid"))).isFalse();
    }

    @Test void freeCreationRequiresFreeContentAndTwoAvailableActiveSlots() {
        assertThat(allowance.beforeCreate(owner, Set.of("lesson-free"))).isTrue();
        assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-paid")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("COURSE_ACCESS_REQUIRED"));
        when(ledger.activeFreePlans(eq(owner), any(), any(), any())).thenReturn(2);
        assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
        assertThatThrownBy(() -> allowance.beforeActivation(owner, UUID.randomUUID(), Set.of("lesson-free")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
    }

    @Test void lifetimeLimitDoesNotUsePlanNumberAndCountsSuccessfulImportViaSameCreatePath() {
        when(ledger.creations(owner)).thenReturn(15);
        assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_LIFETIME_PLAN_LIMIT"));
        verify(ledger, never()).recordCreation(any(), any());
        allowance.afterCreate(owner, UUID.randomUUID(), true);
        verify(ledger).recordCreation(eq(owner), any(UUID.class));
    }

    @Test void freeAccountHasThreeSuccessfulDeletionsPerRollingWindow() {
        when(ledger.deletionsSince(eq(owner), any())).thenReturn(2, 3);
        when(ledger.oldestDeletionSince(eq(owner), any())).thenReturn(Instant.parse("2026-10-01T12:00:00Z"));
        allowance.beforeDelete(owner);
        assertThatThrownBy(() -> allowance.beforeDelete(owner))
                .isInstanceOfSatisfying(AccountFailure.class, e -> {
                    assertThat(e.code()).isEqualTo("FREE_DELETE_LIMIT");
                    assertThat(e.getMessage()).contains("2026-10-22T12:00Z");
                });
        verify(ledger, never()).recordDeletion(any(), any());
        allowance.afterDelete(owner, UUID.randomUUID(), true);
        verify(ledger).recordDeletion(eq(owner), any(UUID.class));
    }

    @Test void convertingPaidScopeToFreeScopeSpendsLifetimeAllowanceOnce() {
        UUID planId = UUID.randomUUID();
        when(ledger.creationRecorded(owner, planId)).thenReturn(false, true);
        assertThat(allowance.beforeFreeScopeChange(owner, planId, Set.of("lesson-paid"),
                Set.of("lesson-free"), true)).isTrue();
        allowance.afterCreate(owner, planId, true);
        assertThat(allowance.beforeFreeScopeChange(owner, planId, Set.of("lesson-paid"),
                Set.of("lesson-free"), true)).isFalse();
        verify(ledger).recordCreation(owner, planId);
    }

    @Test void paidAccountIsNotSubjectToFreeAllowance() {
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of("learn:core-java"));
        assertThat(allowance.beforeCreate(owner, Set.of("lesson-paid"))).isFalse();
        allowance.beforeDelete(owner);
        verify(ledger, never()).creations(any());
        verify(ledger, never()).deletionsSince(any(), any());
    }

    /** DLV-969: each free allowance hit is one lookahead.limit WARN line with the limit, its size and its window. */
    @Test void eachFreeAllowanceHitIsOneLimitLine() {
        try (var log = com.lookahead.domain.LogCapture.start()) {
            when(ledger.activeFreePlans(eq(owner), any(), any(), any())).thenReturn(2);
            assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free"))).isInstanceOf(AccountFailure.class);
            when(ledger.activeFreePlans(eq(owner), any(), any(), any())).thenReturn(0);
            when(ledger.creations(owner)).thenReturn(15);
            assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free"))).isInstanceOf(AccountFailure.class);
            when(ledger.deletionsSince(eq(owner), any())).thenReturn(3);
            when(ledger.oldestDeletionSince(eq(owner), any())).thenReturn(Instant.parse("2026-10-01T12:00:00Z"));
            assertThatThrownBy(() -> allowance.beforeDelete(owner)).isInstanceOf(AccountFailure.class);

            assertThat(log.lines()).allSatisfy(line -> {
                assertThat(line.getLoggerName()).isEqualTo("lookahead.limit");
                assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("account.id", owner).containsEntry("event.outcome", "refused");
            });
            assertThat(log.lines()).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("reason"))
                    .containsExactly("FREE_ACTIVE_PLAN_LIMIT", "FREE_LIFETIME_PLAN_LIMIT", "FREE_DELETE_LIMIT");
            assertThat(com.lookahead.domain.LogCapture.keys(log.lines().get(2))).containsEntry("limit.name", "free-plan-deletions")
                    .containsEntry("limit.max", 3L).containsEntry("limit.window", "21d");
            assertThat(log.messages()).containsExactly(
                    "Free plan limit hit for account " + owner + ": at most 2 active free plans at once",
                    "Free plan limit hit for account " + owner + ": all 15 free plan creations of its lifetime are used",
                    "Free plan limit hit for account " + owner + ": at most 3 deletions in each rolling 21 days; next deletion allowed 2026-10-22T12:00Z");
        }
    }
}
