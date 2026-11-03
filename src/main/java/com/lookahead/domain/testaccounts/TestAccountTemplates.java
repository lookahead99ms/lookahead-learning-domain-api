package com.lookahead.domain.testaccounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DLV-969 S35: the scenarios a test account starts from, shared by the roster, single creation and the test-session
 * picker: {@code learner01}–{@code learner27} (Local's learner scenarios), {@code co-author} and {@code admin}.
 */
public final class TestAccountTemplates {
    private TestAccountTemplates() {}

    public record Template(String id, String label, List<String> roles) {}

    public static List<Template> all() {
        var result = new ArrayList<Template>();
        for (var scenario : LearnerScenario.all()) result.add(new Template(scenario.username(), label(scenario), List.of()));
        result.add(new Template("co-author", "Co-author with the full curriculum", List.of("co-author")));
        result.add(new Template("admin", "Admin with the full curriculum", List.of("admin")));
        return List.copyOf(result);
    }

    public static boolean known(String id) { return all().stream().anyMatch(template -> template.id().equals(id)); }

    public static Set<String> roles(String id) {
        return all().stream().filter(template -> template.id().equals(id)).findFirst().map(t -> Set.copyOf(t.roles())).orElse(Set.of());
    }

    /**
     * Whose test accounts a staff member may create or use: nobody gains a role above their own. Owner: every one;
     * co-author: learners, co-authors and admins; admin: learners and admins; anyone else: none.
     */
    public static boolean allows(String staffRole, Set<String> targetRoles) {
        return switch (staffRole == null ? "" : staffRole) {
            case "owner" -> true;
            case "co-author" -> Set.of("co-author", "admin").containsAll(targetRoles);
            case "admin" -> Set.of("admin").containsAll(targetRoles);
            default -> false;
        };
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
