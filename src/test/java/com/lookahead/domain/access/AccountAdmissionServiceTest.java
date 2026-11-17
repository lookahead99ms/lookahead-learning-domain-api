package com.lookahead.domain.access;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969 admission service and controller on a mocked store; the DynamoDB writes are proven by DynamoAccessStoresDatabaseTest. */
class AccountAdmissionServiceTest {
    final AdmissionStore store=mock(AdmissionStore.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    final StaffAccessService access=mock(StaffAccessService.class);
    final StaffAccessRepository staff=mock(StaffAccessRepository.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    final AccountAdmissionService service=new AccountAdmissionService(store,access,staff);
    final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true,true);
    final UUID waiting=UUID.randomUUID();
    final Instant requested=Instant.parse("2026-11-02T08:00:00Z"),admitted=Instant.parse("2026-11-02T09:30:00Z");

    AccountAdmissionService.Row row(boolean admittedFlag) {
        return new AccountAdmissionService.Row(waiting,"new@example.invalid","New Learner","https://issuer.invalid","subject-1",admittedFlag,null);
    }
    void lockedRowIs(AccountAdmissionService.Row row) {
        passThrough();
        when(store.lock(waiting)).thenReturn(row);
        when(store.admit(eq(owner.accountId()),any(),any(),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();return admitted;});
    }
    void passThrough(){doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get()).when(store).transaction(any());}
    void failOwnerCheck(){doThrow(new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access.")).when(access).requireOwner(owner);}
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status,String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }

    @Test void ownerCheckComesBeforeAnyStoreCall() {
        failOwnerCheck();
        passThrough();
        failsWith(()->service.waiting(owner),403,"OWNER_REQUIRED");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of())),403,"OWNER_REQUIRED");
        verify(store,never()).waiting();verify(store,never()).lock(any());verify(store,never()).admit(any(),any(),any(),any());verifyNoInteractions(staff);
    }
    @Test void waitingListsTheStoresRowsWithTheExpiry() {
        var row=new AccountAdmissionService.Waiting(waiting,"new@example.invalid","New Learner","new@example.invalid",requested,requested.plusSeconds(48*3600),2,List.of());
        when(store.waiting()).thenReturn(List.of(row));
        var result=service.waiting(owner);
        assertThat(result.expiryHours()).isEqualTo(48);
        assertThat(result.waiting()).containsExactly(row);
        assertThat(result.waiting().getFirst().sameEmailAccounts()).isEqualTo(2);
        verify(store,never()).admit(any(),any(),any(),any());
    }
    @Test void admissionRejectsMalformedRequestsBeforeTouchingTheStore() {
        passThrough();
        failsWith(()->service.admit(owner,null),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),null)),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("owner"))),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(null,List.of())),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request("not-a-uuid",List.of())),422,"INVALID_ADMISSION");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString().toUpperCase(Locale.ROOT),List.of())),422,"INVALID_ADMISSION");
        verify(store,never()).lock(any());verify(store,never()).admit(any(),any(),any(),any());verifyNoInteractions(staff);verify(access,times(6)).requireOwner(owner);
    }
    @Test void admissionNeedsAWaitingRow() {
        lockedRowIs(null);
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of())),404,"ACCOUNT_NOT_FOUND");
        lockedRowIs(row(true));
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("admin"))),409,"ACCOUNT_ALREADY_ADMITTED");
        lockedRowIs(new AccountAdmissionService.Row(waiting,"u","d","https://issuer.invalid","subject-1",false,"disabled"));
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of())),409,"ACCOUNT_REMOVED");
        verify(store,never()).admit(any(),any(),any(),any());
        verifyNoInteractions(staff);verify(access,never()).save(any(),any());
    }
    @Test void admissionWithoutRolesAdmitsTheRowWithoutAStaffAssignment() {
        lockedRowIs(row(false));
        var result=service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of()));
        assertThat(result).isEqualTo(new AccountAdmissionService.Admitted(waiting,"new@example.invalid","New Learner",admitted,List.of()));
        verify(store).admit(eq(owner.accountId()),eq(row(false)),eq(List.of()),any());
        verifyNoInteractions(staff);verify(access,never()).save(any(),any());
    }
    @Test void admissionWithRolesCreatesTheStaffAssignmentThroughTheOwnerService() {
        lockedRowIs(row(false));
        var result=service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("tester","admin")));
        assertThat(result.roles()).containsExactly("admin","tester");
        var change=org.mockito.ArgumentCaptor.forClass(StaffAccessService.Change.class);
        verify(access).save(eq(owner),change.capture());
        assertThat(change.getValue().accountId()).isEqualTo(waiting);assertThat(change.getValue().pendingEmail()).isNull();
        assertThat(change.getValue().roles()).containsExactlyInAnyOrder("admin","tester");assertThat(change.getValue().topicGrants()).isEmpty();
        assertThat(change.getValue().fullCurriculum()).isFalse();assertThat(change.getValue().revision()).isZero();assertThat(change.getValue().id()).isNotNull();
        verify(store).admit(eq(owner.accountId()),eq(row(false)),eq(List.of("admin","tester")),any());
    }
    @Test void admissionUpdatesAnExistingAssignmentWithItsIdAndRevision() {
        lockedRowIs(row(false));
        var existing=new StaffAccessRepository.Assignment(UUID.randomUUID(),waiting,null,Set.of("reviewer"),Set.of("java"),true,3);
        when(staff.forAccount(waiting)).thenReturn(existing);
        service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("co-author")));
        verify(access).save(owner,new StaffAccessService.Change(existing.id(),waiting,null,Set.of("co-author"),Set.of("java"),true,3));
    }
    @Test void assignmentFailureInsideTheStoreWriteIsTheCallersAnswer() {
        lockedRowIs(row(false));
        when(access.save(any(),any())).thenThrow(new AccountFailure(409,"ACCESS_ASSIGNMENT_CONFLICT","Reload."));
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("admin"))),409,"ACCESS_ASSIGNMENT_CONFLICT");
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
    @Test void admissionIsLoggedWithIdsAndRolesButNeverTheNameOrEmail() {
        lockedRowIs(row(false));
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
