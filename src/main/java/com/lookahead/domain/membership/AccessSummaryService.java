package com.lookahead.domain.membership;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.time.Clock;
import java.time.Instant;

/** A read projection of actual purchases and grants. Never persisted as a role. */
@Service
public class AccessSummaryService {
    public record Summary(String label, List<String> labels, List<String> grantedCourseIds) {}
    private final AccessSummaryStore store;
    private final StaffAccessService staff;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public AccessSummaryService(AccessSummaryStore store,StaffAccessService staff) {
        this(store,staff,Clock.systemUTC());
    }
    public AccessSummaryService(AccessSummaryStore store,StaffAccessService staff,Clock clock) {
        this.store=store;this.staff=staff;this.clock=clock;
    }
    public Summary get(AccountPrincipal actor,UUID target) {
        if(actor==null||!actor.isEnabled()) throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");
        if(!actor.accountId().equals(target)) {
            var permissions=staff.permissions(actor);
            if(!permissions.manageAccess()&&!permissions.triageFeedback()&&!"co-author".equals(permissions.feedbackRole()))
                throw new AccountFailure(403,"STAFF_REQUIRED","Author, co-author or admin required");
        }
        Instant now=clock.instant();
        var purchases=store.activeSnapshots(target,now);
        var courses=store.grantedTopics(target,now);
        return summarize(purchases,courses);
    }
    static Summary summarize(List<JsonNode> purchases,Set<String> grants) {
        var labels=new TreeSet<String>();
        for(var plan:purchases) {
            String label=("promo".equals(plan.path("group").asText())?"Promo":"Regular")+" · "+plan.path("name").asText();
            if("courses".equals(plan.path("scope").path("kind").asText())) {
                var chosen=new TreeSet<String>();
                plan.path("scope").path("courseIds").forEach(value->chosen.add(value.asText()));
                if(!chosen.isEmpty())label+=" · "+String.join(", ",chosen);
            }
            labels.add(label);
        }
        var courses=new TreeSet<>(com.lookahead.learning.content.util.CatalogIds.curriculumGrants(grants)); // DLV-968: staff scopes are not courses
        if(!courses.isEmpty()) labels.add("Granted · "+String.join(", ",courses));
        if(labels.isEmpty())labels.add("Free");
        return new Summary(String.join(" + ",labels),List.copyOf(labels),List.copyOf(courses));
    }
}
