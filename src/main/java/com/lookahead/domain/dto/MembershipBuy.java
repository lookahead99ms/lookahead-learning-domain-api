package com.lookahead.domain.dto;

import java.util.List;
import java.util.UUID;

/** The body of a purchase quote, a purchase and a staff plan upgrade. DLV-1016: was nested in MembershipService. */
public record MembershipBuy(String planId,UUID replacesId,List<String> chosenCourses,Integer expectedPriceCents) {
    public MembershipBuy(String planId,UUID replacesId,List<String> chosenCourses){this(planId,replacesId,chosenCourses,null);}
}
