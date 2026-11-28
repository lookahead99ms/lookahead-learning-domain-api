package com.lookahead.domain.service.access;

import com.lookahead.domain.dto.AccountView;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.util.CatalogIds;
import org.springframework.stereotype.Service;

/**
 * What a signed-in person sees of the own account: courses, the content they open, author access
 * and staff permissions. DLV-1016: the account controller put this answer together from four
 * services itself.
 */
@Service
public class AccountViewService {
  private final AccountGrantService accounts;
  private final ProtectedContentManifestService publication;
  private final AuthorPreviewLocalService author;
  private final StaffAccessService access;

  public AccountViewService(
      AccountGrantService accounts,
      ProtectedContentManifestService publication,
      AuthorPreviewLocalService author,
      StaffAccessService access) {
    this.accounts = accounts;
    this.publication = publication;
    this.author = author;
    this.access = access;
  }

  public AccountView view(AccountPrincipal principal) {
    // DLV-968: staff scopes stay out of the course list; staff documents are governed by
    // permissions.previewDocuments.
    var grants = CatalogIds.curriculumGrants(accounts.findTopicGrants(principal.accountId()));
    return new AccountView(
        principal.accountId(),
        principal.getUsername(),
        principal.displayName(),
        grants,
        publication.contentGrants(grants),
        author.allowed(principal),
        access.permissions(principal));
  }
}
