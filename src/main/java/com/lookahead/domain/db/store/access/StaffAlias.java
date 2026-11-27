package com.lookahead.domain.db.store.access;

import java.util.UUID;

/**
 * The permanent role-label numbers and the owner flag behind {@code StaffAliasService}. {@link StaffAliasStore}
 * allocates through {@code StaffAliasNumberStore} (a counter plus two permanent insert-only claims).
 */
public interface StaffAlias {
    /** True for the owner's account (cloud author access). */
    boolean owner(UUID accountId);
    /** The person's number for {@code role}, given now if they have none. */
    int number(UUID accountId, String role);
}
