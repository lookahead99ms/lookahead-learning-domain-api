package com.lookahead.domain.membership;

import java.math.*;
import java.time.*;
import java.util.Set;
import com.lookahead.learning.content.exception.AccountFailure;

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
    static AccountFailure invalid(String message){return new AccountFailure(422,"MEMBERSHIP_INVALID",message);}
}
