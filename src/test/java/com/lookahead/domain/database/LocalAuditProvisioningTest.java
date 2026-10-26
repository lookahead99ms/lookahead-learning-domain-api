package com.lookahead.domain.database;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalAuditProvisioningTest {
    private final String local = "jdbc:postgresql://domain-api-db:5432/lookahead_platform";
    @Test void onlyExplicitLocalProvisioningCanRequestSyntheticAuditPurge() {
        assertThat(LocalAuditProvisioning.requested(null, "dev", local)).isFalse();
        assertThat(LocalAuditProvisioning.requested("false", "prod", local)).isFalse();
        assertThat(LocalAuditProvisioning.requested("true", "local", local)).isTrue();
        for (String env : new String[]{"dev", "prod", "", "LOCAL"})
            assertThatThrownBy(() -> LocalAuditProvisioning.requested("true", env, local)).isInstanceOf(IllegalStateException.class);
        for (String flag : new String[]{"TRUE", "1", ""})
            assertThatThrownBy(() -> LocalAuditProvisioning.requested(flag, "local", local)).isInstanceOf(IllegalStateException.class);
        for (String host : new String[]{"lookahead-dev-db.example.us-east-2.rds.amazonaws.com", "other-db", "localhost.evil.invalid"})
            assertThatThrownBy(() -> LocalAuditProvisioning.requested("true", "local", local.replace("domain-api-db", host)))
                    .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LocalAuditProvisioning.requested("true", "local", local.replace("lookahead_platform", "other")))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void grantIsNarrowAndConnectionIsClosed() throws Exception {
        var source = mock(DataSource.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY)); var connection = mock(Connection.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY)); var statement = mock(Statement.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
        when(source.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(statement);
        LocalAuditProvisioning.provision(source);
        verify(statement).setQueryTimeout(5);
        verify(statement).execute("GRANT DELETE ON public.membership_audit TO lookahead_platform_app");
        verify(statement).close(); verify(connection).close();
    }
}
