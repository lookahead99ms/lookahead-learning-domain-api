package com.lookahead.domain.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DLV-970: the per-row facts of the owner's directory ({@link AdminAccountDirectory}) on one store. */
public interface AdminDirectoryStore {
    boolean author(UUID account);
    boolean testAccount(UUID account);
    /** An open purchase row (not superseded). */
    boolean openPurchases(UUID account);
    boolean grants(UUID account);
    /** DLV-969 S36: other accounts, not deleted or test, with this verified email; 0 without an email. */
    int sameEmailAccounts(UUID account, String email);
    /** Each sign-in of the account, oldest first, as {provider, email?}, test identities left out. */
    List<Map<String, Object>> signInMethods(UUID account);
    /** The account's saved GitHub profile link, or null. Read only for the owner's directory (requireOwner ran first). */
    String github(UUID account);
}
