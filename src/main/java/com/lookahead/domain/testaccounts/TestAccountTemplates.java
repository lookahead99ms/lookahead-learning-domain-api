package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.StaffPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DLV-969 S35: the scenarios a test account starts from, shared by the roster, single creation and the test-session
 * picker: {@code learner01}–{@code learner27} (Local's learner scenarios). DLV-971 (owner, 2026-11-06): the
 * {@code co-author} and {@code admin} scenarios are gone; a test account never carries a staff role. The hierarchy
 * ({@link #allows}, {@link #role}) stays, because it also governs the People list and who may act on which account.
 */
public final class TestAccountTemplates {
    private TestAccountTemplates() {}

    public record Template(String id, String label, List<String> roles) {}

    public static List<Template> all() {
        var result = new ArrayList<Template>();
        for (var scenario : LearnerScenario.all()) result.add(new Template(scenario.username(), label(scenario), List.of()));
        return List.copyOf(result);
    }

    public static boolean known(String id) { return all().stream().anyMatch(template -> template.id().equals(id)); }

    /**
     * DLV-969 (owner, 2026-11-04): "Create test account" makes learner test accounts only, for every role, the owner
     * included. DLV-971 (owner, 2026-11-06): every scenario is a learner scenario, so this is the whole list.
     */
    public static List<Template> creatable() { return all(); }

    public static boolean creatable(String id) { return creatable().stream().anyMatch(template -> template.id().equals(id)); }

    /**
     * DLV-969 (owner, 2026-11-04): the one access hierarchy for DEV test accounts and the People list. Who may see or act
     * on an account with {@code targetRoles} ({@code "owner"} for the owner's own account), by the acting person's
     * role from {@link #role}:
     * <ul>
     *   <li>owner: every account;</li>
     *   <li>co-author: every account except the owner's;</li>
     *   <li>admin: admins and learners only (never a co-author or above);</li>
     *   <li>learner, reviewer and tester: no test account; they never switch (owner decision 2026-11-04).</li>
     *   <li>anyone else (not admitted, unknown): nothing.</li>
     * </ul>
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
     * The acting person's place in {@link #allows}: "owner", then the lower of their staff roles ("admin" before
     * "co-author": a person with both is an admin), else "learner". Learners, reviewers and testers never switch
     * (owner decision 2026-11-04).
     * Only call this for an admitted, enabled account; anyone else has no role here.
     */
    public static String role(boolean owner, Set<String> staffRoles) {
        if (owner) return "owner";
        if (staffRoles != null && staffRoles.contains("admin")) return "admin";
        if (staffRoles != null && staffRoles.contains("co-author")) return "co-author";
        return "learner";
    }

    /**
     * {@link #role} from the signed-in person's permissions: the owner manages access; an admin (and only an admin
     * among staff) creates delivery items; a co-author without admin is the co-author feedback role. Pinned against
     * {@link #role(boolean, Set)} for every role combination in the tests.
     */
    public static String role(StaffPermissions permissions) {
        if (permissions.manageAccess()) return "owner";
        if (permissions.deliveryCreate()) return "admin";
        if ("co-author".equals(permissions.feedbackRole())) return "co-author";
        return "learner";
    }

    /** "Learn · ending soon · student discount · custom plan · in progress". */
    static String label(LearnerScenario scenario) {
        var parts = new ArrayList<String>();
        if (scenario.membership() == null) parts.add("Free");
        else {
            parts.add(plan(scenario.membership()));
            parts.add(switch (scenario.term()) { case ACTIVE -> "active"; case ENDING -> "ending soon"; case EXPIRED -> "expired"; default -> ""; });
        }
        if (scenario.upgradedFrom() != null) parts.add((scenario.staffUpgrade() ? "staff upgrade from " : "upgraded from ") + plan(scenario.upgradedFrom()));
        if (scenario.scheduled() != null) parts.add(plan(scenario.scheduled()) + " next");
        if (scenario.extraMembership() != null) parts.add("plus " + plan(scenario.extraMembership()));
        if (scenario.discount() != null) parts.add(scenario.discount() + " discount");
        parts.add(switch (scenario.planKind()) {
            case NONE -> "no study plan"; case READY_MADE -> "ready-made plan"; case CUSTOM -> "custom plan";
            case INTERVIEW -> "interview plan"; case SEVERAL -> "several plans";
        });
        if (scenario.progress() != LearnerScenario.Progress.NONE) parts.add(switch (scenario.progress()) {
            case NOT_STARTED -> "not started"; case IN_PROGRESS -> "in progress"; case FINISHED -> "finished"; case OVERDUE -> "overdue";
            default -> "";
        });
        return String.join(" · ", parts);
    }

    private static String plan(String id) {
        return switch (id) {
            case "interview-sprint" -> "Interview sprint";
            case "monthly-promo" -> "Monthly Promo";
            case "annual-promo" -> "Annual Promo";
            case "interview-pass-promo" -> "Interview pass Promo";
            default -> Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }
}
