package com.lookahead.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The DEV access rows the store reads. DLV-1016: these were nested in DevAccessService; the store uses them too, so they live with the model. */
public final class DevAccessModels {
    private DevAccessModels() {}

    /**
     * sameEmailAccounts (DLV-969 S36, owner-only like the email): other accounts, not deleted or test, with the same
     * verified email. signInMethods: each sign-in of the account, oldest first, as {provider, email?}.
     */
    public record Waiting(UUID accountId,String username,String displayName,String email,Instant requestedAt,Instant expiresAt,int sameEmailAccounts,
            List<Map<String,Object>> signInMethods) {
    }
    /** The account to admit as read for update; {@code state} is the account state ("disabled"/"removed") or null. */
    public record Row(UUID accountId,String username,String displayName,String issuer,String subject,boolean admitted,String state) {}
}
