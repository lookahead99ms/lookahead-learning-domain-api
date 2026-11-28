package com.lookahead.domain.util;

import com.lookahead.domain.model.StaffPermissions;
import java.util.Set;

/**
 * The role hierarchy: a person's acting role, and whose account that role may see or act on. Used
 * by the People list, test accounts and test sessions.
 */
public final class StaffAccessRoleRules {
  private StaffAccessRoleRules() {}

  /**
   * DLV-969 (owner, 2026-11-04): the one access hierarchy for DEV test accounts and the People
   * list. Who may see or act on an account with {@code targetRoles} ({@code "owner"} for the
   * owner's own account), by the acting person's role from {@link #role}:
   *
   * <ul>
   *   <li>owner: every account;
   *   <li>co-author: every account except the owner's;
   *   <li>admin: admins and learners only (never a co-author or above);
   *   <li>learner, reviewer and tester: no test account; they never switch (owner decision
   *       2026-11-04).
   *   <li>anyone else (not admitted, unknown): nothing.
   * </ul>
   *
   * Nobody gains a role above their own.
   */
  public static boolean allows(String actorRole, Set<String> targetRoles) {
    if (targetRoles == null) return false;
    return switch (actorRole == null ? "" : actorRole) {
      case "owner" -> true;
      case "co-author" -> !targetRoles.contains("owner");
      case "admin" -> Set.of("admin").containsAll(targetRoles);
      // Owner decision 2026-11-04: learners, reviewers and testers never switch.
      default -> false;
    };
  }

  /**
   * The acting person's place in {@link #allows}: "owner", then the lower of their staff roles
   * ("admin" before "co-author": a person with both is an admin), else "learner". Learners,
   * reviewers and testers never switch (owner decision 2026-11-04). Only call this for an admitted,
   * enabled account; anyone else has no role here.
   */
  public static String role(boolean owner, Set<String> staffRoles) {
    if (owner) return "owner";
    if (staffRoles != null && staffRoles.contains("admin")) return "admin";
    if (staffRoles != null && staffRoles.contains("co-author")) return "co-author";
    return "learner";
  }

  /**
   * {@link #role} from the signed-in person's permissions: the owner manages access; an admin (and
   * only an admin among staff) creates delivery items; a co-author without admin is the co-author
   * feedback role. Pinned against {@link #role(boolean, Set)} for every role combination in the
   * tests.
   */
  public static String role(StaffPermissions permissions) {
    if (permissions.manageAccess()) return "owner";
    if (permissions.deliveryCreate()) return "admin";
    if ("co-author".equals(permissions.feedbackRole())) return "co-author";
    return "learner";
  }
}
