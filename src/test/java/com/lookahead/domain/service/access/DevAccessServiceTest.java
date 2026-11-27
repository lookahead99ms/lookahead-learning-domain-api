package com.lookahead.domain.service.access;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.dto.DevAccessAdmission;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.DevAccessModels;
import com.lookahead.domain.controller.DevAccessController;
import com.lookahead.domain.db.store.access.DevAccess;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.time.Instant;
import java.util.*;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969 admission service and controller on a mocked store; the DynamoDB writes are proven by StaffAccessStoreDatabaseTest. */
class DevAccessServiceTest {
    final DevAccess store=mock(DevAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final StaffAccessService access=mock(StaffAccessService.class);
    final StaffAccess staff=mock(StaffAccess.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final DevAccessService service=new DevAccessService(store,access,staff);
    final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true,true);
    final UUID waiting=UUID.randomUUID();
    final Instant requested=Instant.parse("2026-11-02T08:00:00Z"),admitted=Instant.parse("2026-11-02T09:30:00Z");

    final DevAccess.Prepared prepared=new DevAccess.Prepared(){};

    DevAccessModels.Row row(boolean admittedFlag) {
        return new DevAccessModels.Row(waiting,"new@example.invalid","New Learner","https://issuer.invalid","subject-1",admittedFlag,null);
    }
    void lockedRowIs(DevAccessModels.Row row) {
        when(store.lock(waiting)).thenReturn(row);
        when(store.prepare(row)).thenReturn(prepared);
        when(store.admit(eq(owner.accountId()),same(prepared),any())).thenReturn(admitted);
    }
    void failOwnerCheck(){doThrow(new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access.")).when(access).requireOwner(owner);}
    static void failsWith(ThrowableAssert.ThrowingCallable call,int status,String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }

    @Test void ownerCheckComesBeforeAnyStoreCall() {
        failOwnerCheck();
        failsWith(()->service.waiting(owner),403,"OWNER_REQUIRED");
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of())),403,"OWNER_REQUIRED");
        verify(store,never()).waiting();verify(store,never()).lock(any());verify(store,never()).admit(any(),any(),any());verifyNoInteractions(staff);
    }
    @Test void waitingListsTheStoresRowsWithTheExpiry() {
        var row=new DevAccessModels.Waiting(waiting,"new@example.invalid","New Learner","new@example.invalid",requested,requested.plusSeconds(48*3600),2,List.of());
        when(store.waiting()).thenReturn(List.of(row));
        var result=service.waiting(owner);
        assertThat(result.expiryHours()).isEqualTo(48);
        assertThat(result.waiting()).containsExactly(row);
        assertThat(result.waiting().getFirst().sameEmailAccounts()).isEqualTo(2);
        verify(store,never()).admit(any(),any(),any());
    }
    @Test void admissionRejectsMalformedRequestsBeforeTouchingTheStore() {
        failsWith(()->service.admit(owner,null),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),null)),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("owner"))),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new DevAccessAdmission(null,List.of())),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new DevAccessAdmission("not-a-uuid",List.of())),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString().toUpperCase(Locale.ROOT),List.of())),422,"INVALID_ADMISSION");
        verify(store,never()).lock(any());verify(store,never()).admit(any(),any(),any());verifyNoInteractions(staff);verify(access,times(6)).requireOwner(owner);
    }
    @Test void admissionNeedsAWaitingRow() {
        lockedRowIs(null);
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of())),404,"ACCOUNT_NOT_FOUND");
        lockedRowIs(row(true));
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("admin"))),409,"ACCOUNT_ALREADY_ADMITTED");
        lockedRowIs(new DevAccessModels.Row(waiting,"u","d","https://issuer.invalid","subject-1",false,"disabled"));
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of())),409,"ACCOUNT_REMOVED");
        verify(store,never()).admit(any(),any(),any());
        verifyNoInteractions(staff);verify(access,never()).save(any(),any());
    }
    @Test void admissionWithoutRolesAdmitsTheRowWithoutAStaffAssignment() {
        lockedRowIs(row(false));
        var result=service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of()));
        assertThat(result).isEqualTo(new DevAccessService.Admitted(waiting,"new@example.invalid","New Learner",admitted,List.of()));
        verify(store).prepare(row(false));verify(store).admit(eq(owner.accountId()),same(prepared),eq(List.of()));
        verifyNoInteractions(staff);verify(access,never()).save(any(),any());
    }
    @Test void admissionWithRolesCreatesTheStaffAssignmentThroughTheOwnerService() {
        lockedRowIs(row(false));
        var result=service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("tester","admin")));
        assertThat(result.roles()).containsExactly("admin","tester");
        var change=ArgumentCaptor.forClass(StaffAccessChange.class);
        verify(access).save(eq(owner),change.capture());
        assertThat(change.getValue().accountId()).isEqualTo(waiting);assertThat(change.getValue().pendingEmail()).isNull();
        assertThat(change.getValue().roles()).containsExactlyInAnyOrder("admin","tester");assertThat(change.getValue().topicGrants()).isEmpty();
        assertThat(change.getValue().fullCurriculum()).isFalse();assertThat(change.getValue().revision()).isZero();assertThat(change.getValue().id()).isNotNull();
        verify(store).prepare(row(false));verify(store).admit(eq(owner.accountId()),same(prepared),eq(List.of("admin","tester")));
    }
    @Test void admissionUpdatesAnExistingAssignmentWithItsIdAndRevision() {
        lockedRowIs(row(false));
        var existing=new StaffAccess.Assignment(UUID.randomUUID(),waiting,null,Set.of("reviewer"),Set.of("java"),true,3);
        when(staff.forAccount(waiting)).thenReturn(existing);
        service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("co-author")));
        verify(access).save(owner,new StaffAccessChange(existing.id(),waiting,null,Set.of("co-author"),Set.of("java"),true,3));
    }
    @Test void assignmentFailureInsideTheStoreWriteIsTheCallersAnswer() {
        lockedRowIs(row(false));
        when(access.save(any(),any())).thenThrow(new AccountFailure(409,"ACCESS_ASSIGNMENT_CONFLICT","Reload."));
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("admin"))),409,"ACCESS_ASSIGNMENT_CONFLICT");
        verify(store,never()).admit(any(),any(),any());
    }
    @Test void anAccountThatVanishedBeforeTheWriteIsRefusedBeforeAnyRoleIsAssigned() {
        lockedRowIs(row(false));
        when(store.prepare(row(false))).thenReturn(null);
        failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("admin"))),404,"ACCOUNT_NOT_FOUND");
        verify(access,never()).save(any(),any());verify(store,never()).admit(any(),any(),any());
    }
    @Test void controllerDisablesCachingAndDelegates() {
        var devAccess=mock(DevAccessService.class);var controller=new DevAccessController(devAccess);
        var waitingList=new DevAccessService.WaitingList(List.of(),48);when(devAccess.waiting(owner)).thenReturn(waitingList);
        var request=new DevAccessAdmission(waiting.toString(),List.of("tester"));
        var result=new DevAccessService.Admitted(waiting,"u","d",admitted,List.of("tester"));when(devAccess.admit(owner,request)).thenReturn(result);
        var first=new MockHttpServletResponse();var second=new MockHttpServletResponse();
        assertThat(controller.waiting(owner,first).data()).isSameAs(waitingList);
        assertThat(controller.admit(owner,request,second).data()).isSameAs(result);
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(second.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /** DLV-969: an admission is one INFO line with ids and roles, a refusal one WARN line with its code; never the name or email. */
    @Test void admissionIsLoggedWithIdsAndRolesButNeverTheNameOrEmail() {
        lockedRowIs(row(false));
        try(var log=LogCapture.start()){
            service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of("tester","admin")));
            var line=log.only("admission.admitted");
            assertThat(line.getLoggerName()).isEqualTo("lookahead.event");
            assertThat(line.getLevel()).isEqualTo(Level.INFO);
            assertThat(line.getFormattedMessage()).isEqualTo("Owner admitted account "+waiting+" with roles [admin, tester]");
            assertThat(LogCapture.keys(line)).containsEntry("event.outcome","success").containsEntry("account.id",owner.accountId())
                .containsEntry("target.id",waiting).containsEntry("roles",List.of("admin","tester"));
            lockedRowIs(row(true));
            failsWith(()->service.admit(owner,new DevAccessAdmission(waiting.toString(),List.of())),409,"ACCOUNT_ALREADY_ADMITTED");
            var refused=log.only("admission.admit");
            assertThat(refused.getLevel()).isEqualTo(Level.WARN);
            assertThat(refused.getFormattedMessage()).isEqualTo("Admission refused: ACCOUNT_ALREADY_ADMITTED");
            assertThat(LogCapture.keys(refused)).containsEntry("reason","ACCOUNT_ALREADY_ADMITTED").containsEntry("event.outcome","refused");
            log.containsNone("new@example.invalid","New Learner","subject-1","issuer.invalid");
        }
    }
}
