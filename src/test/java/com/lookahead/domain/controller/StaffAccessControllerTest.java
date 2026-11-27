package com.lookahead.domain.controller;

import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.service.access.AccountDirectoryService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.access.StaffAliasService;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 (owner decision 2026-11-03): only the owner may look anyone up. Who sees which identity in the list is
 * {@code StaffAliasService.assignments} (StaffAliasServiceAssignmentsTest); the exact JSON is pinned in
 * StaffAccessControllerIdentityJsonTest.
 */
class StaffAccessControllerTest {
    final StaffAccessService access=mock(StaffAccessService.class);
    final AccountDirectoryService directory=mock(AccountDirectoryService.class);
    final StaffAliasService aliases=mock(StaffAliasService.class);
    final StaffAccessController controller=new StaffAccessController(access,directory,aliases);
    final AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true,true);
    final AccountPrincipal coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
    final UUID person=UUID.randomUUID();

    @Test void theListIsWhatTheAliasServiceAnswersForTheCallerAndIsNeverCached() {
        List<StaffAccess.Assignment> rows=List.of(
            new StaffAccess.Assignment(UUID.randomUUID(),person,null,Set.of("co-author"),Set.of("learn:java"),false,3));
        doReturn(rows).when(aliases).assignments(owner);
        var response=new MockHttpServletResponse();
        assertThat(controller.list(owner,response).data()).isSameAs(rows);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        // The controller decides nothing: it reads no row and asks for no label itself.
        verify(aliases).assignments(owner);verifyNoMoreInteractions(aliases);verifyNoInteractions(access);
    }
    @Test void aRefusalToOpenTheListLeavesTheControllerAsItIs() {
        doThrow(new AccountFailure(403,"ACCESS_READER_REQUIRED","Owner or co-author required.")).when(aliases).assignments(coauthor);
        var response=new MockHttpServletResponse();
        assertThatThrownBy(()->controller.list(coauthor,response))
            .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("ACCESS_READER_REQUIRED"));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void lookingSomeoneUpIsOwnerOnly() {
        // DLV-1016: the owner check is AccountDirectoryService.lookup(actor, query); the controller hands the refusal on.
        when(directory.lookup(coauthor,"someone@example.invalid")).thenThrow(new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access."));
        assertThatThrownBy(()->controller.lookup(coauthor,Map.of("query","someone@example.invalid"),new MockHttpServletResponse()))
            .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(403));
        var found=new AccountDirectoryModels.Account(person,"someone","Someone","someone@example.invalid");
        when(directory.lookup(owner,"someone@example.invalid")).thenReturn(found);
        assertThat(controller.lookup(owner,Map.of("query","someone@example.invalid"),new MockHttpServletResponse()).data()).isEqualTo(found);
    }
}
