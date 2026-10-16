package com.lookahead.domain.access;

import java.util.Set;

/** Product permissions are separate from authentication and curriculum grants. */
public record StaffPermissions(boolean manageAccess, boolean previewDocuments, boolean deliveryRead,
        boolean deliveryCreate, boolean deliveryReview, boolean deliveryDone, boolean triageFeedback,
        boolean fullCurriculum, String feedbackRole) {
    public static final Set<String> ROLES = Set.of("co-author", "admin", "reviewer", "tester");

    public static StaffPermissions forRoles(boolean owner, Set<String> roles) {
        if (roles == null || !ROLES.containsAll(roles)) throw new IllegalArgumentException("Unsupported staff role");
        boolean coauthor = roles.contains("co-author");
        boolean admin = roles.contains("admin"), reviewer = roles.contains("reviewer"), tester = roles.contains("tester");
        return new StaffPermissions(owner, owner || coauthor || admin || reviewer, owner || coauthor || admin || reviewer,
                owner || admin, owner || admin, owner || reviewer, owner || admin,
                coauthor || admin || tester, owner ? "owner" : coauthor ? "co-author" : admin ? "admin" : reviewer ? "reviewer" : tester ? "tester" : "user");
    }

    /** The local editor invokes this after loading the authoritative previous item. */
    public boolean permitsDeliveryWrite(boolean creating, String previousStatus, String nextStatus) {
        if (manageAccess) return true;
        if (creating) return deliveryCreate && Set.of("backlog", "in-progress", "review").contains(nextStatus);
        if ("done".equals(nextStatus)) return deliveryDone && "review".equals(previousStatus);
        return deliveryReview && !"done".equals(previousStatus) && "review".equals(nextStatus);
    }
}
