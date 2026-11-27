package com.lookahead.domain.util;

import com.lookahead.domain.model.TestAccountScenario;
import java.util.ArrayList;
import java.util.List;

/**
 * DLV-969 S35: the scenarios a test account starts from, shared by the roster, single creation and the test-session
 * picker: {@code learner01}–{@code learner27} (Local's learner scenarios). DLV-971 (owner, 2026-11-06): the
 * {@code co-author} and {@code admin} scenarios are gone; a test account never carries a staff role. The hierarchy
 * ({@link com.lookahead.domain.util.StaffAccessRoleRules}) stays, because it also governs the People list and who may act on which account.
 */
public final class TestAccountTemplates {
    private TestAccountTemplates() {}

    public record Template(String id, String label, List<String> roles) {}

    public static List<Template> all() {
        var result = new ArrayList<Template>();
        for (var scenario : TestAccountScenario.all()) result.add(new Template(scenario.username(), label(scenario), List.of()));
        return List.copyOf(result);
    }

    public static boolean known(String id) { return all().stream().anyMatch(template -> template.id().equals(id)); }

    /**
     * DLV-969 (owner, 2026-11-04): "Create test account" makes learner test accounts only, for every role, the owner
     * included. DLV-971 (owner, 2026-11-06): every scenario is a learner scenario, so this is the whole list.
     */
    public static List<Template> creatable() { return all(); }


    /** "Learn · ending soon · student discount · custom plan · in progress". */
    static String label(TestAccountScenario scenario) {
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
        if (scenario.progress() != TestAccountScenario.Progress.NONE) parts.add(switch (scenario.progress()) {
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
