package com.lookahead.domain.model;

import java.time.*;
import java.util.*;

/** Membership shapes shared by the service and its store. DLV-1016: these were nested in MembershipService; the store uses them too, so they live with the model. */
public final class MembershipModels {
    private MembershipModels() {}

    public record Sale(String template,String greeting,Instant startsAt,Instant endsAt,List<String> planIds) {}
}
