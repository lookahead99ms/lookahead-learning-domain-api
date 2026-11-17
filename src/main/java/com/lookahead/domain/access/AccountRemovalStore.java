package com.lookahead.domain.access;

import com.lookahead.domain.access.AccountRemovalService.Changed;
import com.lookahead.domain.access.AccountRemovalService.Target;
import java.util.UUID;

/**
 * DLV-970: the owner's Disable, Enable and Delete of a DEV sign-in account on one store: the checks (not an author
 * account, not a test account, not already deleted), the state change, ended sign-ins, erasure for Delete, and the
 * admission event when something changed. DynamoDB commits the state change with its terminal marker and erasure job in one fenced transaction and runs
 * the erasure after it, resumable by a retried Delete or the account-erasure sweep. The policy refusals use
 * {@link AccountRemovalService}'s codes. {@link #revokeSignIns} and {@link #erase} served the retired PostgreSQL merge;
 * the DynamoDB merge composes its own transaction.
 */
public interface AccountRemovalStore {
    /** author, admitted and state of the account for the directory flags; null when there is no such account. */
    Target options(UUID id);
    Changed disable(UUID actor, UUID id);
    Changed enable(UUID actor, UUID id);
    Changed remove(UUID actor, UUID id);
    /** Ends every sign-in and pending sign-in choice of the account. */
    void revokeSignIns(UUID id);
    /** Roles, grants, plans, receipts and profile settings; purchase history stays for the audit. */
    void erase(UUID actor, UUID id);
}
