package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Author preview access uses the current account capability and trusted catalog grants. */
@Service
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name = "app.deployment-environment", havingValue = "local")
@ConditionalOnProperty(name = "app.local-test.author-enabled", havingValue = "true")
public class AuthorPreviewAccessService {
    private final LocalAuthorAccess authorAccess;
    private final AccountRepository accounts;
    private final SnapshotValidator catalog;

    public AuthorPreviewAccessService(LocalAuthorAccess authorAccess, AccountRepository accounts,
                                      SnapshotValidator catalog) {
        this.authorAccess = authorAccess;
        this.accounts = accounts;
        this.catalog = catalog;
    }

    public void requireAccess(AccountPrincipal principal) {
        if (!authorAccess.allowed(principal)) throw denied();
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
