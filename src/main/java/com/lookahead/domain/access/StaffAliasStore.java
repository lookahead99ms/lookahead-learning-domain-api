package com.lookahead.domain.access;

import java.util.UUID;

/**
 * DLV-970: the permanent role-label numbers and the owner flag behind {@link StaffAliases}. {@link DynamoStaffAliasStore}
 * allocates through the
 * {@code DynamoStaffAliasStore} library (counter plus two permanent insert-only claims).
 */
public interface StaffAliasStore {
    /** True for the owner's account (cloud author access). */
    boolean owner(UUID accountId);
    /** The person's number for {@code role}, given now if they have none. */
    int number(UUID accountId, String role);
}
