package com.lookahead.domain.testaccounts;

import java.util.Set;
import java.util.UUID;

/** The Local test-account deletion hierarchy, shared by the list and mutation paths. */
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
}
