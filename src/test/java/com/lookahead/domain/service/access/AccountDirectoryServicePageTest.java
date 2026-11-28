package com.lookahead.domain.service.access;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.access.AccountDirectory;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.Mockito;

class AccountDirectoryServicePageTest {
  @Test
  void rejectsInvalidQueriesAndBoundsBeforeDatabaseAccess() {
    var store = mock(AccountDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var directory = new AccountDirectoryService(store, mock(StaffAccessService.class));
    for (String query : new String[] {null, "x".repeat(255), "a\nb"})
      assertThatThrownBy(() -> directory.list(query, 0)).isInstanceOf(AccountFailure.class);
    for (int page : new int[] {-1, 100001})
      assertThatThrownBy(() -> directory.list("", page)).isInstanceOf(AccountFailure.class);
    verifyNoInteractions(store);
    when(store.list("", 0)).thenReturn(new AccountDirectoryModels.Page(List.of(), 0, false));
    assertThat(directory.list("", 0).accounts()).isEmpty();
    assertThat(directory.list("", 0).hasNext()).isFalse();
  }

  /**
   * DLV-1016: the owner check that was the controller's first statement is the first thing
   * lookup(actor, query) does.
   */
  @Test
  void lookupForAnActorChecksTheOwnerBeforeTheQueryIsLookedAt() {
    var store = mock(AccountDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var staff = mock(StaffAccessService.class);
    var directory = new AccountDirectoryService(store, staff);
    var learner = new AccountPrincipal(UUID.randomUUID(), "learner", "Learner", true);
    var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);
    Mockito.doThrow(new AccountFailure(403, "OWNER_REQUIRED", "Only the owner can manage access."))
        .when(staff)
        .requireOwner(learner);
    Assertions.assertThatThrownBy(() -> directory.lookup(learner, null))
        .isInstanceOfSatisfying(
            AccountFailure.class, f -> Assertions.assertThat(f.code()).isEqualTo("OWNER_REQUIRED"));
    Mockito.verifyNoInteractions(store);
    var found =
        new AccountDirectoryModels.Account(
            UUID.randomUUID(), "someone", "Someone", "someone@example.invalid");
    when(store.lookup("someone@example.invalid")).thenReturn(Optional.of(found));
    Assertions.assertThat(directory.lookup(owner, " Someone@Example.invalid ")).isEqualTo(found);
    Mockito.verify(staff).requireOwner(owner);
  }
}
