package com.lookahead.domain.access;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969 admission service and controller on a mocked JdbcTemplate; the SQL itself is proven by the database suite. */
class AccountAdmissionServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final StaffAccessService access=mock(StaffAccessService.class);
    final StaffAccessRepository staff=mock(StaffAccessRepository.class);
    final AccountAdmissionService service=new AccountAdmissionService(jdbc,access,staff,new ObjectMapper());
    final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true,true);
    final UUID waiting=UUID.randomUUID();
    final Instant requested=Instant.parse("2026-11-02T08:00:00Z"),admitted=Instant.parse("2026-11-02T09:30:00Z");

    ResultSet row(boolean admittedFlag) throws Exception {
        var rs=mock(ResultSet.class,withSettings().mockMaker(MockMakers.SUBCLASS)); // interface mock: subclass maker, as in the other suites
        when(rs.getObject("account_id",UUID.class)).thenReturn(waiting);
        when(rs.getString("username")).thenReturn("new@example.invalid");when(rs.getString("display_name")).thenReturn("New Learner");
        when(rs.getString("verified_email")).thenReturn("new@example.invalid");when(rs.getTimestamp("requested_at")).thenReturn(Timestamp.from(requested));
        when(rs.getString("issuer")).thenReturn("https://issuer.invalid");when(rs.getString("subject")).thenReturn("subject-1");
        when(rs.getBoolean("admitted")).thenReturn(admittedFlag);
        return rs;
    }
    @SuppressWarnings("unchecked") void lockedRowIs(ResultSet rs) {
        when(jdbc.query(contains("FOR UPDATE"),any(RowMapper.class),eq(waiting))).thenAnswer(call->rs==null?List.of():List.of(((RowMapper<Object>)call.getArgument(1)).mapRow(rs,0)));
    }
    void failOwnerCheck(){doThrow(new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access.")).when(access).requireOwner(owner);}
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status,String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }

    @Test void ownerCheckComesBeforeAnyQuery() {
        failOwnerCheck();
        failsWith(()->service.waiting(owner),403,"OWNER_REQUIRED");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of())),403,"OWNER_REQUIRED");
        verifyNoInteractions(jdbc,staff);
    }
    @Test @SuppressWarnings("unchecked") void waitingListsUnadmittedRowsWithTheirExpiry() throws Exception {
        var rs=row(false);
        when(jdbc.query(contains("WHERE NOT admitted"),any(RowMapper.class))).thenAnswer(call->List.of(((RowMapper<Object>)call.getArgument(1)).mapRow(rs,0)));
        var result=service.waiting(owner);
        assertThat(result.expiryHours()).isEqualTo(48);
        assertThat(result.waiting()).containsExactly(new AccountAdmissionService.Waiting(waiting,"new@example.invalid","New Learner","new@example.invalid",requested,requested.plusSeconds(48*3600)));
        verify(jdbc).query(contains("ORDER BY requested_at,account_id"),any(RowMapper.class));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void admissionRejectsMalformedRequestsBeforeTouchingTheDatabase() {
        failsWith(()->service.admit(owner,null),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),null)),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("owner"))),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(null,List.of())),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request("not-a-uuid",List.of())),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString().toUpperCase(Locale.ROOT),List.of())),422,"INVALID_ADMISSION");
        verifyNoInteractions(jdbc,staff);verify(access,times(6)).requireOwner(owner);
    }
    @Test void admissionNeedsAWaitingRow() throws Exception {
        lockedRowIs(null);
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of())),404,"ACCOUNT_NOT_FOUND");
        lockedRowIs(row(true));
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("admin"))),409,"ACCOUNT_ALREADY_ADMITTED");
        verify(jdbc,never()).update(anyString(),any(Object[].class));verify(jdbc,never()).queryForObject(anyString(),eq(Timestamp.class),any(Object[].class));
        verifyNoInteractions(staff);verify(access,never()).save(any(),any());
    }
    @Test void admissionWithoutRolesMarksTheRowAndRecordsTheEvent() throws Exception {
        lockedRowIs(row(false));
        when(jdbc.queryForObject(contains("RETURNING admitted_at"),eq(Timestamp.class),eq(owner.accountId()),eq(waiting))).thenReturn(Timestamp.from(admitted));
        var result=service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of()));
        assertThat(result).isEqualTo(new AccountAdmissionService.Admitted(waiting,"new@example.invalid","New Learner",admitted,List.of()));
        verify(jdbc).queryForObject(contains("SET admitted=true,admitted_at=clock_timestamp(),admitted_by=?"),eq(Timestamp.class),eq(owner.accountId()),eq(waiting));
        verify(jdbc).update(contains("INSERT INTO cloud_admission_events"),any(UUID.class),eq(waiting),eq("https://issuer.invalid"),eq("subject-1"),eq(owner.accountId()),eq("[]"));
        verifyNoInteractions(staff);verify(access,never()).save(any(),any());
    }
    @Test void admissionWithRolesCreatesTheStaffAssignmentThroughTheOwnerService() throws Exception {
        lockedRowIs(row(false));
        when(jdbc.queryForObject(contains("RETURNING admitted_at"),eq(Timestamp.class),any(),any())).thenReturn(Timestamp.from(admitted));
        var result=service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("tester","admin")));
        assertThat(result.roles()).containsExactly("admin","tester");
        var change=org.mockito.ArgumentCaptor.forClass(StaffAccessService.Change.class);
        verify(access).save(eq(owner),change.capture());
        assertThat(change.getValue().accountId()).isEqualTo(waiting);assertThat(change.getValue().pendingEmail()).isNull();
        assertThat(change.getValue().roles()).containsExactlyInAnyOrder("admin","tester");assertThat(change.getValue().topicGrants()).isEmpty();
        assertThat(change.getValue().fullCurriculum()).isFalse();assertThat(change.getValue().revision()).isZero();assertThat(change.getValue().id()).isNotNull();
        verify(jdbc).update(contains("INSERT INTO cloud_admission_events"),any(UUID.class),eq(waiting),anyString(),anyString(),eq(owner.accountId()),eq("[\"admin\",\"tester\"]"));
    }
    @Test void admissionUpdatesAnExistingAssignmentWithItsIdAndRevision() throws Exception {
        lockedRowIs(row(false));
        when(jdbc.queryForObject(contains("RETURNING admitted_at"),eq(Timestamp.class),any(),any())).thenReturn(Timestamp.from(admitted));
        var existing=new StaffAccessRepository.Assignment(UUID.randomUUID(),waiting,null,Set.of("reviewer"),Set.of("java"),true,3);
        when(staff.forAccount(waiting)).thenReturn(existing);
        service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("co-author")));
        verify(access).save(owner,new StaffAccessService.Change(existing.id(),waiting,null,Set.of("co-author"),Set.of("java"),true,3));
    }
    @Test void assignmentFailureAbortsBeforeTheEventIsRecorded() throws Exception {
        lockedRowIs(row(false));
        when(jdbc.queryForObject(contains("RETURNING admitted_at"),eq(Timestamp.class),any(),any())).thenReturn(Timestamp.from(admitted));
        when(access.save(any(),any())).thenThrow(new AccountFailure(409,"ACCESS_ASSIGNMENT_CONFLICT","Reload."));
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("admin"))),409,"ACCESS_ASSIGNMENT_CONFLICT");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void controllerDisablesCachingAndDelegates() {
        var admission=mock(AccountAdmissionService.class);var controller=new AccountAdmissionController(admission);
        var waitingList=new AccountAdmissionService.WaitingList(List.of(),48);when(admission.waiting(owner)).thenReturn(waitingList);
        var request=new AccountAdmissionService.Request(waiting.toString(),List.of("tester"));
        var result=new AccountAdmissionService.Admitted(waiting,"u","d",admitted,List.of("tester"));when(admission.admit(owner,request)).thenReturn(result);
        var first=new MockHttpServletResponse();var second=new MockHttpServletResponse();
        assertThat(controller.waiting(owner,first).data()).isSameAs(waitingList);
        assertThat(controller.admit(owner,request,second).data()).isSameAs(result);
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(second.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /** DLV-969: an admission is one INFO line with ids and roles, a refusal one WARN line with its code; never the name or email. */
    @Test void admissionIsLoggedWithIdsAndRolesButNeverTheNameOrEmail() throws Exception {
        lockedRowIs(row(false));
        when(jdbc.queryForObject(contains("RETURNING admitted_at"),eq(Timestamp.class),any(),any())).thenReturn(Timestamp.from(admitted));
        try(var log=com.lookahead.domain.LogCapture.start()){
            service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("tester","admin")));
            var line=log.only("admission.admitted");
            assertThat(line.getLoggerName()).isEqualTo("lookahead.event");
            assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(line.getFormattedMessage()).isEqualTo("Owner admitted account "+waiting+" with roles [admin, tester]");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("event.outcome","success").containsEntry("account.id",owner.accountId())
                .containsEntry("target.id",waiting).containsEntry("roles",List.of("admin","tester"));
            lockedRowIs(row(true));
            failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of())),409,"ACCOUNT_ALREADY_ADMITTED");
            var refused=log.only("admission.admit");
            assertThat(refused.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(refused.getFormattedMessage()).isEqualTo("Admission refused: ACCOUNT_ALREADY_ADMITTED");
            assertThat(com.lookahead.domain.LogCapture.keys(refused)).containsEntry("reason","ACCOUNT_ALREADY_ADMITTED").containsEntry("event.outcome","refused");
            log.containsNone("new@example.invalid","New Learner","subject-1","issuer.invalid");
        }
    }
}
