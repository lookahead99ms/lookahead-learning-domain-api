package com.lookahead.domain.access;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-969 (owner decision 2026-11-03): only the owner sees who an assignment is for, and only the owner may look anyone up. */
class StaffAccessControllerTest {
    final StaffAccessService access=mock(StaffAccessService.class);
    final AccountDirectory directory=mock(AccountDirectory.class);
    final StaffAliases aliases=mock(StaffAliases.class);
    final StaffAccessController controller=new StaffAccessController(access,directory,aliases);
    final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true,true);
    final AccountPrincipal coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
    final UUID person=UUID.randomUUID();
    final List<StaffAccessRepository.Assignment> rows=List.of(
        new StaffAccessRepository.Assignment(UUID.randomUUID(),person,null,Set.of("co-author"),Set.of("learn:java"),false,3),
        new StaffAccessRepository.Assignment(UUID.randomUUID(),null,"invitee@example.invalid",Set.of("tester"),Set.of(),true,1));

    @Test void theOwnerGetsTheAssignmentsAsTheyAre() {
        when(access.list(owner)).thenReturn(rows);when(aliases.seesIdentities(owner)).thenReturn(true);
        var response=new MockHttpServletResponse();
        assertThat(controller.list(owner,response).data()).isEqualTo(rows);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(aliases,never()).label(any());
    }
    @Test void aCoAuthorGetsLabelsAndNeverAnAccountIdOrInvitationEmail() {
        when(access.list(coauthor)).thenReturn(rows);when(aliases.label(person)).thenReturn("Co-author 2");
        @SuppressWarnings("unchecked") var shared=(List<StaffAccessController.SharedAssignment>)controller.list(coauthor,new MockHttpServletResponse()).data();
        assertThat(shared).extracting(StaffAccessController.SharedAssignment::label).containsExactly("Co-author 2","Pending invitation");
        assertThat(shared).extracting(StaffAccessController.SharedAssignment::pending).containsExactly(false,true);
        assertThat(shared).allSatisfy(row->{assertThat(row.accountId()).isNull();assertThat(row.pendingEmail()).isNull();});
        assertThat(shared.getFirst().roles()).containsExactly("co-author");assertThat(shared.getFirst().topicGrants()).containsExactly("learn:java");
        assertThat(shared.getFirst().revision()).isEqualTo(3);assertThat(shared.get(1).fullCurriculum()).isTrue();
        assertThat(shared.getFirst().id()).isEqualTo(rows.getFirst().id());
    }
    @Test void aCoAuthorStillSeesTheirOwnRowWithTheirOwnName() {
        var own=new StaffAccessRepository.Assignment(UUID.randomUUID(),coauthor.accountId(),null,Set.of("co-author"),Set.of(),false,2);
        when(access.list(coauthor)).thenReturn(List.of(own,rows.getFirst()));when(aliases.label(person)).thenReturn("Co-author 2");
        @SuppressWarnings("unchecked") var shared=(List<StaffAccessController.SharedAssignment>)controller.list(coauthor,new MockHttpServletResponse()).data();
        assertThat(shared.getFirst().label()).isEqualTo("Co");assertThat(shared.getFirst().accountId()).isEqualTo(coauthor.accountId());
        assertThat(shared.get(1).label()).isEqualTo("Co-author 2");assertThat(shared.get(1).accountId()).isNull();
        verify(aliases,never()).label(coauthor.accountId());
        // No display name: their username.
        var unnamed=new AccountPrincipal(coauthor.accountId(),"coauthor@example.invalid"," ",true,false);
        when(access.list(unnamed)).thenReturn(List.of(own));
        @SuppressWarnings("unchecked") var alone=(List<StaffAccessController.SharedAssignment>)controller.list(unnamed,new MockHttpServletResponse()).data();
        assertThat(alone.getFirst().label()).isEqualTo("coauthor@example.invalid");
    }
    @Test void lookingSomeoneUpIsOwnerOnly() {
        doThrow(new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access.")).when(access).requireOwner(coauthor);
        assertThatThrownBy(()->controller.lookup(coauthor,Map.of("query","someone@example.invalid"),new MockHttpServletResponse()))
            .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(403));
        verifyNoInteractions(directory);
        var found=new AccountDirectory.Account(person,"someone","Someone","someone@example.invalid");
        when(directory.lookup("someone@example.invalid")).thenReturn(found);
        assertThat(controller.lookup(owner,Map.of("query","someone@example.invalid"),new MockHttpServletResponse()).data()).isEqualTo(found);
    }
}
