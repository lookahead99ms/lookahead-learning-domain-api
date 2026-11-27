package com.lookahead.domain.service.access;

import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.util.AccountDirectoryRules;
import com.lookahead.domain.model.AccountDirectoryModels.*;
import com.lookahead.domain.db.store.access.AccountDirectory;
import com.lookahead.domain.exception.AccountFailure;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * The account directory of DEV and PROD: the accounts are in the store. DLV-1016: Local, where the Identity service has
 * them, is {@link AccountDirectoryLocalService}; the two conditions choose what the {@code identity == null} branches
 * of this class chose, by the same setting as the Identity client's own bean.
 */
@Service
@ConditionalOnExpression("'${app.deployment-environment:}' != 'local'")
public class AccountDirectoryService {
    private final AccountDirectory store;
    private final StaffAccessService staff;
    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @Autowired
    public AccountDirectoryService(AccountDirectory store, StaffAccessService staff) {
        this.staff = staff;
        this.store=store;
    }
    public Page list(String query,int page) {
        if(query==null||query.length()>254||query.codePoints().anyMatch(Character::isISOControl)||page<0||page>100000)
            throw new AccountFailure(422,"INVALID_ACCOUNT_DIRECTORY","Use a query up to 254 characters and a page from 0 to 100000.");
        String term=query.strip().toLowerCase(Locale.ROOT);
        return page(term,page);
    }
    /** One page of the directory, for a checked term and page. */
    Page page(String term,int page) {
        // DLV-969: disabled accounts stay listed (the owner can enable them); deleted ones are gone. The owner sees
        // the provider-verified email (a Google account's Gmail).
        return store.list(term,page);
    }
    /** The owner looks one person up. DLV-1016: the owner check was the controller's; it is still made before the query is looked at. */
    public Account lookup(AccountPrincipal actor, String query) {
        staff.requireOwner(actor);
        return lookup(query);
    }
    private Account lookup(String query) {
        if(query==null||query.isBlank()||query.length()>254||query.codePoints().anyMatch(Character::isISOControl))throw invalid();
        query=query.strip().toLowerCase(Locale.ROOT);
        return match(query);
    }
    /** The one account a checked query names. */
    Account match(String query) {
        // Cloud identities already admitted by the provider mapping; no email-based linking. DLV-969 S36: any Cognito
        // username of the account (V19 cloud_identities), or its own username for a row without identity rows.
        return store.lookup(query).orElseThrow(AccountDirectoryRules::missing);
    }
    public Account byId(UUID id) {
        if (id == null) throw invalid();
        return account(id);
    }
    /** The account with this id. */
    Account account(UUID id) {
        return store.byId(id).orElseThrow(AccountDirectoryRules::missing);
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ACCOUNT_LOOKUP","Enter an exact email or username.");}
}
