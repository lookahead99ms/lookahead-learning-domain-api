package com.lookahead.domain.util;

import java.math.*;
import java.time.*;
import java.util.HashSet;
import java.util.Set;
import com.lookahead.domain.exception.AccountFailure;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

public final class MembershipRules {
    private MembershipRules() {}
    public static final Set<String> YEARLY=Set.of("learn","grow","advance","complete");
    public static boolean upgrade(Set<String> from,Set<String> to) { return to.size()>from.size()&&to.containsAll(from); }
    public static Instant end(Instant start,String unit,int count) {
        var utc=start.atZone(ZoneOffset.UTC);
        return switch(unit){case "years"->utc.plusYears(count).toInstant();case "months"->utc.plusMonths(count).toInstant();case "days"->utc.plusDays(count).toInstant();default->throw invalid("Plan duration is pending");};
    }
    /** Provisional user-approved handoff default; one rounding boundary, integer cents. */
    public static int prorateHalfUpCents(int difference,Instant start,Instant end,Instant now) {
        if(difference<0||!end.isAfter(start))throw invalid("Invalid upgrade price or term");
        long total=Duration.between(start,end).toMillis(),remaining=Math.max(0,Math.min(total,Duration.between(now,end).toMillis()));
        return BigDecimal.valueOf(difference).multiply(BigDecimal.valueOf(remaining)).divide(BigDecimal.valueOf(total),0,RoundingMode.HALF_UP).intValueExact();
    }
    public static void requireChangeWindow(Instant now,Instant end) {
        if(now.isBefore(end.minus(Duration.ofDays(7)))||!now.isBefore(end))throw new AccountFailure(409,"CHANGE_WINDOW_CLOSED","Choose a different plan in the final seven days");
    }
    /** DEV: an active test account that staff created on the owner's domain. {@code registry} is the stored registry row, or null. DLV-1016: this rule was in the membership store. */
    public static boolean staffCreatedTestAccount(JsonNode registry) {
        if (registry == null) return false;
        var creator = registry.path("creatorId");
        return "active".equals(registry.path("creationState").asText()) && !(creator.isNull() || creator.isMissingNode())
                && registry.path("username").asText().endsWith("@lookahead.dev");
    }
    /** The purchase statuses that can give access: paid for ({@code succeeded}) or waiting for its start ({@code scheduled}). */
    private static final Set<String> STANDING = Set.of("succeeded", "scheduled");
    /** A field that is absent or stored as NULL: both read as "not set". */
    private static boolean unset(JsonNode field) { return field == null || field.isNull() || field.isMissingNode(); }
    /** The purchase unlocks its plan at {@code now}: succeeded or scheduled, not superseded, started and not ended. DLV-1016: this rule was in the membership items helper. */
    public static boolean grantsAt(JsonNode p, Instant now) {
        return STANDING.contains(p.path("status").asText()) && unset(p.path("supersededAt"))
                && !Instant.parse(p.path("startsAt").asText()).isAfter(now) && Instant.parse(p.path("endsAt").asText()).isAfter(now);
    }
    /**
     * A paid membership (the merge page's check): a succeeded or scheduled purchase still running, not superseded, that was paid for or whose
     * plan is priced. {@code planOf} reads the plan definition by id and is asked only when nothing was paid. DLV-1016: this rule was in the membership facts store.
     */
    public static boolean paid(JsonNode p, Instant now, Function<String, ? extends JsonNode> planOf) {
        if (!unset(p.path("supersededAt")) || !STANDING.contains(p.path("status").asText())) return false;
        if (!Instant.parse(p.path("endsAt").asText()).isAfter(now)) return false;
        if (p.path("pricePaidCents").asInt() > 0) return true;
        var plan = planOf.apply(p.path("planId").asText());
        return plan != null && plan.path("priceCents").asInt() > 0;
    }
    public static AccountFailure invalid(String message){return new AccountFailure(422,"MEMBERSHIP_INVALID",message);}
    /** The topics a membership unlocks: every catalog topic whose scope (the part before the colon) is one of the purchased scopes. DLV-1016: this loop was in the grants store. */
    public static Set<String> topics(Set<String> scopes, Set<String> allTopicIds) {
        var result = new HashSet<String>();
        for (String topic : allTopicIds) if (scopes.contains(topic.split(":", 2)[0])) result.add(topic);
        return Set.copyOf(result);
    }
}
