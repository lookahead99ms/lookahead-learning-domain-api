package com.lookahead.domain.db.store.access;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Staff assignments (roles, topic grants, full curriculum) and pending invitations by email, with their audit events.
 * DLV-970: {@link StaffAccessStore} is the store, where {@link #save} is one revision-fenced transaction with
 * its uniqueness sentinels (the PostgreSQL store retired 2026-11-17).
 */
public abstract class StaffAccess {
    public record Assignment(UUID id, UUID accountId, String pendingEmail, Set<String> roles,
            Set<String> topicGrants, boolean fullCurriculum, long revision) {}
    public abstract Assignment forAccount(UUID account);
    public abstract List<Assignment> list();
    /** DLV-969: the owner's account ids (cloud author access); the People list never shows their row to other staff. */
    public abstract Set<UUID> owners();
    /** The assignment to change, read for update; null when there is none. */
    public abstract Assignment lock(UUID id);
    /** A stored invitation with its age by the store's clock. Whether it still counts (unclaimed, same email, young enough, granting something) is the caller's rule. */
    public record Invitation(Assignment assignment, Duration age) {}
    /** The invitation stored for the email, or null when there is none. */
    public abstract Invitation pending(String email);
    public abstract void insert(Assignment a);
    public abstract void update(Assignment a);
    public abstract void event(UUID actor, String action, Assignment old, Assignment next);
    /** One whole change: the row inserted or updated at {@code old}'s revision and its event, together. */
    public abstract void save(UUID actor, String action, Assignment old, Assignment next);
}
