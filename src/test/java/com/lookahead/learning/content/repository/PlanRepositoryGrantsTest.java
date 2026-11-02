package com.lookahead.learning.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.learning.content.util.PlanJson;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** DLV-968: plan access checks see curriculum grants only; the staff documents scope authorizes no plan content. */
class PlanRepositoryGrantsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final UUID account = UUID.randomUUID();

    @Test void staffScopeIsDroppedFromPlanGrants() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(account))).thenReturn(List.of("look-ahead:system-design"));
        when(accounts.findTopicGrants(account)).thenReturn(Set.of("staff:documents", "learn:core-java"));
        var repository = new PlanRepository(jdbc, new PlanJson(new JsonMapper()));
        repository.accounts(accounts);
        assertThat(repository.grants(account)).containsExactlyInAnyOrder("blaze:system-design", "learn:core-java");
    }
}
