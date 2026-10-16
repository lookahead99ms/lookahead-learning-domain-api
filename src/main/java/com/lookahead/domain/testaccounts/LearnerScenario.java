package com.lookahead.domain.testaccounts;

import java.time.*;
import java.util.*;
import com.lookahead.domain.membership.MembershipRules;

/** Fixed synthetic defaults, not account state or an authorization source. */
public record LearnerScenario(int number, String membership, Term term, String discount,
                              PlanKind planKind, Progress progress, String scheduled,
                              String upgradedFrom, boolean staffUpgrade, String extraMembership) {
    public enum Term { NONE, ACTIVE, ENDING, EXPIRED }
    public enum PlanKind { NONE, READY_MADE, CUSTOM, INTERVIEW, SEVERAL }
    public enum Progress { NONE, NOT_STARTED, IN_PROGRESS, FINISHED, OVERDUE }
    public record Dates(Instant startsAt, Instant endsAt) {}
    public String username() { return "learner%02d".formatted(number); }
    public static LearnerScenario of(int number) {
        return all().stream().filter(row -> row.number == number).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown learner scenario"));
    }
    public static List<LearnerScenario> all() {
        return List.of(
            row(1, null, Term.NONE, null, PlanKind.NONE, Progress.NONE),
            row(2, null, Term.NONE, null, PlanKind.READY_MADE, Progress.IN_PROGRESS),
            row(3, null, Term.NONE, null, PlanKind.CUSTOM, Progress.OVERDUE),
            row(4, null, Term.NONE, null, PlanKind.READY_MADE, Progress.NOT_STARTED),
            row(5, "learn", Term.ACTIVE, null, PlanKind.READY_MADE, Progress.IN_PROGRESS),
            row(6, "learn", Term.ENDING, "student", PlanKind.CUSTOM, Progress.IN_PROGRESS),
            row(7, "learn", Term.EXPIRED, null, PlanKind.READY_MADE, Progress.FINISHED),
            row(8, "grow", Term.ACTIVE, "regional", PlanKind.READY_MADE, Progress.IN_PROGRESS),
            row(9, "grow", Term.ENDING, null, PlanKind.CUSTOM, Progress.OVERDUE),
            row(10, "grow", Term.EXPIRED, null, PlanKind.NONE, Progress.NONE),
            row(11, "advance", Term.ACTIVE, "student", PlanKind.SEVERAL, Progress.IN_PROGRESS),
            new LearnerScenario(12,"advance",Term.ENDING,null,PlanKind.READY_MADE,Progress.IN_PROGRESS,"grow",null,false,null),
            row(13, "advance", Term.EXPIRED, "regional", PlanKind.READY_MADE, Progress.FINISHED),
            row(14, "complete", Term.ACTIVE, "regional", PlanKind.SEVERAL, Progress.IN_PROGRESS),
            new LearnerScenario(15,"complete",Term.ENDING,null,PlanKind.CUSTOM,Progress.IN_PROGRESS,"advance",null,false,null),
            row(16, "complete", Term.EXPIRED, null, PlanKind.READY_MADE, Progress.OVERDUE),
            new LearnerScenario(17,"advance",Term.ACTIVE,null,PlanKind.READY_MADE,Progress.IN_PROGRESS,null,"learn",false,null),
            new LearnerScenario(18,"complete",Term.ACTIVE,"student",PlanKind.CUSTOM,Progress.NOT_STARTED,null,"grow",false,null),
            new LearnerScenario(19,"complete",Term.ACTIVE,null,PlanKind.READY_MADE,Progress.IN_PROGRESS,null,"learn",true,null),
            row(20, "interview-sprint", Term.ACTIVE, null, PlanKind.INTERVIEW, Progress.IN_PROGRESS),
            row(21, "interview-sprint", Term.ENDING, null, PlanKind.INTERVIEW, Progress.OVERDUE),
            row(22, "interview-sprint", Term.EXPIRED, null, PlanKind.INTERVIEW, Progress.FINISHED),
            row(23, "monthly-promo", Term.ACTIVE, "sale", PlanKind.READY_MADE, Progress.NOT_STARTED),
            row(24, "annual-promo", Term.ACTIVE, "sale", PlanKind.SEVERAL, Progress.IN_PROGRESS),
            row(25, "interview-pass-promo", Term.ACTIVE, "sale", PlanKind.INTERVIEW, Progress.IN_PROGRESS),
            new LearnerScenario(26,"monthly-promo",Term.EXPIRED,"sale",PlanKind.READY_MADE,Progress.FINISHED,null,null,false,"interview-pass-promo"),
            new LearnerScenario(27,"advance",Term.ACTIVE,null,PlanKind.SEVERAL,Progress.IN_PROGRESS,null,null,false,"interview-sprint")
        );
    }
    private static LearnerScenario row(int n, String plan, Term term, String discount, PlanKind kind, Progress progress) {
        return new LearnerScenario(n,plan,term,discount,kind,progress,null,null,false,null);
    }
    /** Calendar duration is the purchased duration; ending/expired fixtures retain a real full term. */
    public Dates dates(Instant now, String unit, int count) {
        if (term == Term.NONE) throw new IllegalArgumentException("No purchased term");
        if (term == Term.ACTIVE) {
            Instant start = now.minus(Duration.ofDays(upgradedFrom == null ? 7 : 60));
            return new Dates(start, MembershipRules.end(start,unit,count));
        }
        Instant end = term == Term.ENDING ? now.plus(Duration.ofDays(4)) : now.minus(Duration.ofDays(10));
        var utc = end.atZone(ZoneOffset.UTC);
        Instant start = switch(unit) {
            case "years" -> utc.minusYears(count).toInstant();
            case "months" -> utc.minusMonths(count).toInstant();
            case "days" -> utc.minusDays(count).toInstant();
            default -> throw new IllegalArgumentException("Unsupported fixture duration");
        };
        return new Dates(start,end);
    }
}
