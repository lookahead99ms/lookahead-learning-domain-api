package com.lookahead.domain.client;

import com.lookahead.domain.model.AccountDirectoryModels.Account;
import com.lookahead.domain.model.AccountDirectoryModels.Page;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The account directory of an environment whose accounts live outside this service. Only Local has one: the Identity
 * service ({@code local.identity.IdentityAccountClient}). In DEV and PROD the accounts are in this service's own store
 * and there is no such bean.
 */
public interface AccountDirectoryClient {
    /** {@code term} and {@code page} are already checked by the caller. */
    Page list(String term, int page);
    /** {@code found} runs for a usable answer before the call is logged as done. */
    Account lookup(String query, Consumer<Account> found);
    Account byId(UUID id);
}
