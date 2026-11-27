package com.lookahead.domain.service.content;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.StaffAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Author preview access uses the current account capability and trusted catalog grants. */
@Service
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name = "app.deployment-environment", havingValue = "local")
@ConditionalOnProperty(name = "app.local-test.author-enabled", havingValue = "true")
public class AuthorPreviewAccessService {
    private final StaffAccessService staff;
    private final AccountGrantService accounts;
    private final AccountCatalogService catalog;

    public AuthorPreviewAccessService(StaffAccessService staff, AccountGrantService accounts,
                                      AccountCatalogService catalog) {
        this.staff = staff;
        this.accounts = accounts;
        this.catalog = catalog;
    }

    public void requireAccess(AccountPrincipal principal) {
        if (!staff.permissions(principal).previewDocuments()) throw denied();
        var requiredTopics = catalog.allTopicIds();
        if (requiredTopics.isEmpty()
                || !accounts.findTopicGrants(principal.accountId()).containsAll(requiredTopics)) {
            throw denied();
        }
    }

    private static AccountFailure denied() {
        return new AccountFailure(403, "AUTHOR_PREVIEW_ACCESS_DENIED", "Author preview access is unavailable");
    }
}
