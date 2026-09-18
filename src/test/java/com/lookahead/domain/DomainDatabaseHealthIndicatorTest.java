package com.lookahead.domain;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DomainDatabaseHealthIndicatorTest {
    @Test void readinessRequiresAnExplicitDatabaseConfirmation() {
        var jdbc = mock(JdbcTemplate.class);
        var indicator = new DomainDatabaseHealthIndicator(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true, false, null);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test void inaccessibleOrIncompleteSchemaFailsWithoutDatabaseDetails() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenThrow(
                new DataAccessResourceFailureException("private database endpoint and credentials"));
        var health = new DomainDatabaseHealthIndicator(jdbc).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }
}
