package com.lookahead.domain.cloud;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969 cleanup on a mocked JdbcTemplate; locking and the trigger are proven by the database suite. */
class CloudAdmissionCleanupTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    // Interface mocks use the subclass maker, as the other suites do: inline interface mocks trip Mockito's maker check once another maker is registered.
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final CloudAdmissionCleanup cleanup=new CloudAdmissionCleanup(jdbc,manager);

    void lockAcquired(boolean acquired){when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock"),eq(Boolean.class),eq("cloud-admission-cleanup"))).thenReturn(acquired);}

    @Test void expiryIsSharedWithTheWaitingList(){assertThat(CloudAdmissionCleanup.WAITING_EXPIRY.toHours()).isEqualTo(48);}
    @Test void doesNothingWhenAnotherTaskHoldsTheLock() {
        lockAcquired(false);
        assertThat(cleanup.expire()).isZero();
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(manager).commit(any());
    }
    @Test @SuppressWarnings("unchecked") void expiresEachWaitingRowWithAnEventAndBothDeletes() throws Exception {
        lockAcquired(true);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var rows=List.of(resultSet(first),resultSet(second));
        when(jdbc.query(contains("FOR UPDATE SKIP LOCKED"),any(RowMapper.class),eq(48))).thenAnswer(call->{
            var mapper=(RowMapper<Object>)call.getArgument(1);var mapped=new java.util.ArrayList<>();for(var rs:rows)mapped.add(mapper.mapRow(rs,mapped.size()));return mapped;
        });
        assertThat(cleanup.expire()).isEqualTo(2);
        for(UUID id:List.of(first,second)){
            verify(jdbc).update(contains("INSERT INTO cloud_admission_events"),any(UUID.class),eq(id),eq("https://issuer.invalid"),eq("subject-"+id));
            verify(jdbc).update(eq("DELETE FROM cloud_accounts WHERE account_id=? AND admitted=false"),eq(id));
            verify(jdbc).update(eq("DELETE FROM platform_subjects WHERE id=?"),eq(id));
        }
        verify(jdbc).query(contains("WHERE NOT admitted AND requested_at < clock_timestamp() - make_interval(hours => ?)"),any(RowMapper.class),eq(48));
        verify(manager).commit(any());verify(manager,never()).rollback(any());
    }
    @Test void scheduledRunSwallowsFailuresSoTheSchedulerKeepsRunning() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenThrow(new IllegalStateException("synthetic database outage"));
        assertThatCode(cleanup::run).doesNotThrowAnyException();
        verify(manager).rollback(any());
        assertThatThrownBy(cleanup::expire).isInstanceOf(IllegalStateException.class);
    }
    @Test @SuppressWarnings("unchecked") void scheduledRunRemovesAndReportsWithoutThrowing() throws Exception {
        lockAcquired(true);UUID id=UUID.randomUUID();var rs=resultSet(id);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(48))).thenAnswer(call->List.of(((RowMapper<Object>)call.getArgument(1)).mapRow(rs,0)));
        assertThatCode(cleanup::run).doesNotThrowAnyException();
        verify(jdbc).update(eq("DELETE FROM platform_subjects WHERE id=?"),eq(id));verify(manager).commit(any());
    }
    static ResultSet resultSet(UUID id) throws Exception {
        var rs=mock(ResultSet.class,withSettings().mockMaker(MockMakers.SUBCLASS));
        when(rs.getObject("account_id",UUID.class)).thenReturn(id);when(rs.getString("issuer")).thenReturn("https://issuer.invalid");when(rs.getString("subject")).thenReturn("subject-"+id);
        return rs;
    }
}
