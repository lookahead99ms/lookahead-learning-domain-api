package com.lookahead.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void cloudReadinessRequiresCloudSchemaAndRestrictedIdentityPrivileges(String mode) {
        var jdbc=mock(JdbcTemplate.class);
        var cloud=new DomainDatabaseHealthIndicator(jdbc,new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment",mode));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true,false,true,true);
        assertThat(cloud.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(cloud.health().getStatus()).isEqualTo(Status.UP);
        verify(jdbc,times(4)).queryForObject(anyString(),eq(Boolean.class));
    }
}
