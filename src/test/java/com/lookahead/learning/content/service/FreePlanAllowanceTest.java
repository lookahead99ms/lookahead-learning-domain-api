package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.util.PlanJson;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreePlanAllowanceTest {
    private final UUID owner = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ProtectedContentPolicy publication = mock(ProtectedContentPolicy.class);
    private final FreePlanAllowance allowance = new FreePlanAllowance(jdbc, accounts, publication,
            new PlanJson(new JsonMapper()), Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach void freeAccount() {
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of());
        when(publication.freeContentIds()).thenReturn(Set.of("lesson-free"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(owner))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("free_plan_creations"), eq(Integer.class), eq(owner))).thenReturn(0);
    }

    @Test void freeCreationRequiresFreeContentAndTwoAvailableActiveSlots() {
        assertThat(allowance.beforeCreate(owner, Set.of("lesson-free"))).isTrue();
        assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-paid")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("COURSE_ACCESS_REQUIRED"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(owner))).thenReturn(List.of(true, true));
        assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
        assertThatThrownBy(() -> allowance.beforeActivation(owner, UUID.randomUUID(), Set.of("lesson-free")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_ACTIVE_PLAN_LIMIT"));
    }

    @Test void lifetimeLimitDoesNotUsePlanNumberAndCountsSuccessfulImportViaSameCreatePath() {
        when(jdbc.queryForObject(contains("free_plan_creations"), eq(Integer.class), eq(owner))).thenReturn(15);
        assertThatThrownBy(() -> allowance.beforeCreate(owner, Set.of("lesson-free")))
                .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FREE_LIFETIME_PLAN_LIMIT"));
        verify(jdbc, never()).queryForObject(contains("last_plan_number"), eq(Integer.class), any());
        allowance.afterCreate(owner, UUID.randomUUID(), true);
        verify(jdbc).update(startsWith("INSERT INTO free_plan_creations"), eq(owner), any(UUID.class));
    }

    @Test void freeAccountHasThreeSuccessfulDeletionsPerRollingWindow() {
        when(jdbc.queryForObject(contains("free_plan_deletions"), eq(Integer.class), eq(owner), any()))
                .thenReturn(2, 3);
        when(jdbc.queryForObject(contains("min(deleted_at)"), eq(java.sql.Timestamp.class), eq(owner), any()))
                .thenReturn(java.sql.Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")));
        allowance.beforeDelete(owner);
        assertThatThrownBy(() -> allowance.beforeDelete(owner))
                .isInstanceOfSatisfying(AccountFailure.class, e -> {
                    assertThat(e.code()).isEqualTo("FREE_DELETE_LIMIT");
                    assertThat(e.getMessage()).contains("2026-10-22T12:00Z");
                });
        verify(jdbc, never()).update(startsWith("INSERT INTO free_plan_deletions"), any(), any());
        allowance.afterDelete(owner, UUID.randomUUID(), true);
        verify(jdbc).update(startsWith("INSERT INTO free_plan_deletions"), eq(owner), any(UUID.class));
    }

    @Test void convertingPaidScopeToFreeScopeSpendsLifetimeAllowanceOnce() {
        UUID planId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("free_plan_creations WHERE account_id=? AND plan_id=?"),
                eq(Integer.class), eq(owner), eq(planId))).thenReturn(0, 1);
        assertThat(allowance.beforeFreeScopeChange(owner, planId, Set.of("lesson-paid"),
                Set.of("lesson-free"), true)).isTrue();
        allowance.afterCreate(owner, planId, true);
        assertThat(allowance.beforeFreeScopeChange(owner, planId, Set.of("lesson-paid"),
                Set.of("lesson-free"), true)).isFalse();
        verify(jdbc).update(contains("ON CONFLICT DO NOTHING"), eq(owner), eq(planId));
    }

    @Test void paidAccountIsNotSubjectToFreeAllowance() {
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of("learn:core-java"));
        assertThat(allowance.beforeCreate(owner, Set.of("lesson-paid"))).isFalse();
        allowance.beforeDelete(owner);
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), any());
    }
}
