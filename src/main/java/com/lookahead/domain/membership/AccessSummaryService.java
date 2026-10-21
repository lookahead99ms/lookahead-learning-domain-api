package com.lookahead.domain.membership;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.time.Clock;
import java.sql.Timestamp;

/** A read projection of actual purchases and grants. Never persisted as a role. */
@Service
public class AccessSummaryService {
    public record Summary(String label, List<String> labels, List<String> grantedCourseIds) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StaffAccessService staff;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public AccessSummaryService(JdbcTemplate jdbc,ObjectMapper json,StaffAccessService staff) {
        this(jdbc,json,staff,Clock.systemUTC());
    }
    public AccessSummaryService(JdbcTemplate jdbc,ObjectMapper json,StaffAccessService staff,Clock clock) {
        this.jdbc=jdbc;this.json=json;this.staff=staff;this.clock=clock;
    }
    public Summary get(AccountPrincipal actor,UUID target) {
        if(actor==null||!actor.isEnabled()) throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");
        if(!actor.accountId().equals(target)) {
            var permissions=staff.permissions(actor);
            if(!permissions.manageAccess()&&!permissions.triageFeedback()&&!"co-author".equals(permissions.feedbackRole()))
                throw new AccountFailure(403,"STAFF_REQUIRED","Author, co-author or admin required");
        }
        Timestamp now=Timestamp.from(clock.instant());
        var purchases=jdbc.query("""
            SELECT plan_snapshot FROM membership_purchases WHERE account_id=?
            AND status IN ('succeeded','scheduled') AND superseded_at IS NULL
            AND starts_at<=? AND ends_at>? ORDER BY plan_id,id
            """,(r,n)->json.readTree(r.getString(1)),target,now,now);
        var courses=new TreeSet<>(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id=? AND (valid_until IS NULL OR valid_until>?)",String.class,target,now));
        for(String grants:jdbc.queryForList("SELECT topic_grants::text FROM staff_access WHERE account_id=?",String.class,target))
            json.readTree(grants).forEach(value->courses.add(value.asText()));
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
        var courses=new TreeSet<>(grants);
        if(!courses.isEmpty()) labels.add("Granted · "+String.join(", ",courses));
        if(labels.isEmpty())labels.add("Free");
        return new Summary(String.join(" + ",labels),List.copyOf(labels),List.copyOf(courses));
    }
}
