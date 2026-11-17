package com.lookahead.domain.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Staff assignments (roles, topic grants, full curriculum) and pending invitations by email, with their audit events.
 * DLV-970: {@link DynamoStaffAccessRepository} is the store, where {@link #save} is one revision-fenced transaction with
 * its uniqueness sentinels (the PostgreSQL store retired 2026-11-17).
 */
public abstract class StaffAccessRepository {
    public record Assignment(UUID id, UUID accountId, String pendingEmail, Set<String> roles,
            Set<String> topicGrants, boolean fullCurriculum, long revision) {}
    /** Runs {@code work}; on DynamoDB every store method is its own transaction, so the default runs it as it is. */
    public <T> T transaction(Supplier<T> work) { return work.get(); }
    /** The audit actor of the following writes (a session setting of the retired PostgreSQL store); a no-op on DynamoDB. */
    public abstract void mutationActor(UUID actor);
    public abstract Assignment forAccount(UUID account);
    public abstract List<Assignment> list();
    /** DLV-969: the owner's account ids (cloud author access); the People list never shows their row to other staff. */
    public abstract Set<UUID> owners();
    /** The assignment to change, read for update; null when there is none. */
    public abstract Assignment lock(UUID id);
    /** The live pending invitation for a normalised email (14 days, with something to grant), or null. */
    public abstract Assignment pending(String email);
    public abstract void insert(Assignment a);
    public abstract void update(Assignment a);
    public abstract void event(UUID actor, String action, Assignment old, Assignment next);
    /** One whole change: the row inserted or updated at {@code old}'s revision and its event, together. */
    public void save(UUID actor, String action, Assignment old, Assignment next) {
        mutationActor(actor);
        if (old == null) insert(next); else update(next);
        event(actor, action, old, next);
    }
}
