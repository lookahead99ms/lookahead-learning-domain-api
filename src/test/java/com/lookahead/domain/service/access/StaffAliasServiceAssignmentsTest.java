package com.lookahead.domain.service.access;

import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.access.StaffAlias;
import com.lookahead.domain.dto.StaffAccessSharedAssignment;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 (owner decision 2026-11-03): only the owner sees who an assignment is for; everyone else sees role labels and
 * their own row. DLV-1016: the rule moved from StaffAccessController into {@link StaffAliasService#assignments}, and
 * these cases moved with it from StaffAccessControllerTest.
 */
class StaffAliasServiceAssignmentsTest {
    final StaffAccessService access=mock(StaffAccessService.class);
    final StaffAlias store=mock(StaffAlias.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final StaffAccess staff=mock(StaffAccess.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final StaffAliasService aliases=new StaffAliasService(store,access,staff);
    final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true,true);
    final AccountPrincipal coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
    final UUID person=UUID.randomUUID();
    final List<StaffAccess.Assignment> rows=List.of(
        new StaffAccess.Assignment(UUID.randomUUID(),person,null,Set.of("co-author"),Set.of("learn:java"),false,3),
        new StaffAccess.Assignment(UUID.randomUUID(),null,"invitee@example.invalid",Set.of("tester"),Set.of(),true,1));

    StaffAliasServiceAssignmentsTest() {
        when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true,Set.of()));
        when(access.permissions(any(AccountPrincipal.class))).thenAnswer(call->StaffPermissions.forRoles(call.getArgument(0)==owner,call.getArgument(0)==owner?Set.of():Set.of("co-author")));
        when(staff.forAccount(person)).thenReturn(rows.getFirst());
        when(store.number(person,"co-author")).thenReturn(2);
    }

    @Test void theOwnerGetsTheAssignmentsAsTheyAre() {
        when(access.list(owner)).thenReturn(rows);
        assertThat(aliases.assignments(owner)).isSameAs(rows);
        verifyNoInteractions(store,staff);
    }
    @Test void aCoAuthorGetsLabelsAndNeverAnAccountIdOrInvitationEmail() {
        when(access.list(coauthor)).thenReturn(rows);
        var shared=shared(coauthor);
        assertThat(shared).extracting(StaffAccessSharedAssignment::label).containsExactly("Co-author 2","Pending invitation");
        assertThat(shared).extracting(StaffAccessSharedAssignment::pending).containsExactly(false,true);
        assertThat(shared).allSatisfy(row->{assertThat(row.accountId()).isNull();assertThat(row.pendingEmail()).isNull();});
        assertThat(shared.getFirst().roles()).containsExactly("co-author");assertThat(shared.getFirst().topicGrants()).containsExactly("learn:java");
        assertThat(shared.getFirst().revision()).isEqualTo(3);assertThat(shared.get(1).fullCurriculum()).isTrue();
        assertThat(shared.getFirst().id()).isEqualTo(rows.getFirst().id());
    }
    @Test void aCoAuthorStillSeesTheirOwnRowWithTheirOwnName() {
        var own=new StaffAccess.Assignment(UUID.randomUUID(),coauthor.accountId(),null,Set.of("co-author"),Set.of(),false,2);
        when(access.list(coauthor)).thenReturn(List.of(own,rows.getFirst()));
        var shared=shared(coauthor);
        assertThat(shared.getFirst().label()).isEqualTo("Co");assertThat(shared.getFirst().accountId()).isEqualTo(coauthor.accountId());
        assertThat(shared.get(1).label()).isEqualTo("Co-author 2");assertThat(shared.get(1).accountId()).isNull();
        verify(store,never()).number(eq(coauthor.accountId()),any());verify(staff,never()).forAccount(coauthor.accountId());
        // No display name: their username.
        var unnamed=new AccountPrincipal(coauthor.accountId(),"coauthor@example.invalid"," ",true,false);
        when(access.list(unnamed)).thenReturn(List.of(own));
        assertThat(shared(unnamed).getFirst().label()).isEqualTo("coauthor@example.invalid");
    }
    /** The list is read before anything else, so a caller who may not open it is refused by that check and sees no row. */
    @Test void aCallerWhoMayNotOpenTheListIsRefusedBeforeAnyIdentityCheck() {
        when(access.list(coauthor)).thenThrow(new AccountFailure(403,"ACCESS_READER_REQUIRED","Owner or co-author required."));
        assertThatThrownBy(()->aliases.assignments(coauthor)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("ACCESS_READER_REQUIRED"));
        verify(access,never()).permissions(any());verifyNoInteractions(store,staff);
    }
    /** When the owner check itself fails the answer is labels, never identities (fail closed). */
    @Test void aFailedOwnerCheckAnswersLabelsNotIdentities() {
        when(access.list(owner)).thenReturn(rows);
        when(access.permissions(owner)).thenThrow(new IllegalStateException("store down"));
        var shared=shared(owner);
        assertThat(shared).allSatisfy(row->{assertThat(row.accountId()).isNull();assertThat(row.pendingEmail()).isNull();});
    }
    @SuppressWarnings("unchecked") private List<StaffAccessSharedAssignment> shared(AccountPrincipal viewer){return (List<StaffAccessSharedAssignment>)aliases.assignments(viewer);}
}
