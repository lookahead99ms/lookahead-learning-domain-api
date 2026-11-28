package com.lookahead.domain.service.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DLV-1016: the answer of /api/v1/auth/me, put together by one service instead of by the
 * controller.
 */
class AccountViewServiceTest {
  private final AccountGrantService accounts = mock(AccountGrantService.class);
  private final ProtectedContentManifestService publication =
      mock(ProtectedContentManifestService.class);
  private final AuthorPreviewLocalService author = mock(AuthorPreviewLocalService.class);
  private final StaffAccessService access = mock(StaffAccessService.class);
  private final AccountViewService views =
      new AccountViewService(accounts, publication, author, access);
  private final AccountPrincipal person =
      new AccountPrincipal(UUID.randomUUID(), "admin", "Admin", true);

  @Test
  void theViewListsCoursesWithoutStaffScopesAndCarriesAuthorAccessAndPermissions() {
    var permissions = StaffPermissions.forRoles(false, Set.of("admin"));
    when(accounts.findTopicGrants(person.accountId()))
        .thenReturn(Set.of("staff:documents", "learn:core-java"));
    when(publication.contentGrants(Set.of("learn:core-java")))
        .thenReturn(Set.of("learn:core-java:intro"));
    when(author.allowed(person)).thenReturn(true);
    when(access.permissions(person)).thenReturn(permissions);
    var view = views.view(person);
    assertThat(view.accountId()).isEqualTo(person.accountId());
    assertThat(view.username()).isEqualTo("admin");
    assertThat(view.displayName()).isEqualTo("Admin");
    assertThat(view.topicGrants()).containsExactly("learn:core-java");
    assertThat(view.contentGrants()).containsExactly("learn:core-java:intro");
    assertThat(view.authorPreview()).isTrue();
    assertThat(view.permissions()).isSameAs(permissions);
  }

  @Test
  void theFourReadsHappenInTheOrderTheControllerMadeThem() {
    when(accounts.findTopicGrants(person.accountId())).thenReturn(Set.of());
    views.view(person);
    var order = inOrder(accounts, publication, author, access);
    order.verify(accounts).findTopicGrants(person.accountId());
    order.verify(publication).contentGrants(Set.of());
    order.verify(author).allowed(person);
    order.verify(access).permissions(person);
  }
}
