package com.lookahead.domain.testaccounts;

import java.util.Set;
import java.util.UUID;

/** Who may delete a test account: Local's role hierarchy, and DEV's creator rule; shared by the list and mutation paths. */
final class TestAccountDeletionPolicy {
    private TestAccountDeletionPolicy() {}

    static boolean allows(String actorRole, UUID actorId, UUID targetId, Set<String> targetRoles) {
        if (actorId == null || targetId == null || actorId.equals(targetId)) return false;
        if ("owner".equals(actorRole)) return true;
        if (targetRoles.contains("co-author")) return false;
        if ("co-author".equals(actorRole))
            return targetRoles.isEmpty() || targetRoles.contains("admin");
        return "admin".equals(actorRole) && targetRoles.isEmpty();
    }

    /**
     * DLV-969 (owner, 2026-11-04), DEV: the 35 roster accounts are never deleted, only reset to their scenario. A created
     * test account is deleted by the owner or by the person who created it, whatever their role.
     */
    static boolean allowsInDev(boolean owner, UUID actorId, UUID targetId, UUID creatorId) {
        if (actorId == null || targetId == null || actorId.equals(targetId) || TestAccountRoster.isRoster(targetId)) return false;
        return owner || actorId.equals(creatorId);
    }
}
