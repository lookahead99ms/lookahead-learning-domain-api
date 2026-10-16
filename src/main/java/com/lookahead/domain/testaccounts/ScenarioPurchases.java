package com.lookahead.domain.testaccounts;

import com.lookahead.domain.membership.MembershipRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Writes historical synthetic purchases into the ordinary membership model.
 * Only the guarded transactional test-account service may call this component. */
@Component
public class ScenarioPurchases {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TestAccountPolicy policy;
    public ScenarioPurchases(JdbcTemplate jdbc, ObjectMapper json, TestAccountPolicy policy) {
        this.jdbc=jdbc; this.json=json; this.policy=policy;
    }
    public void populate(UUID account, UUID actor, LearnerScenario scenario, Instant now) {
        policy.requireEnabled();
        if (scenario.membership() == null) return;
        ObjectNode plan = definition(scenario.membership());
        var dates = scenario.dates(now, plan.path("duration").path("kind").asText(), plan.path("duration").path("count").asInt());
        UUID prior = null;
        int price = plan.path("priceCents").asInt();
        Instant starts = dates.startsAt();
        if (scenario.upgradedFrom() != null) {
            var original = definition(scenario.upgradedFrom());
            prior = insert(account, account, original, dates, original.path("priceCents").asInt(),
                    scenario.discount(), null, false, now.minus(Duration.ofDays(10)));
            starts = now.minus(Duration.ofDays(10));
            price = scenario.staffUpgrade() ? 0 : MembershipRules.prorateHalfUpCents(
                    price-original.path("priceCents").asInt(),dates.startsAt(),dates.endsAt(),starts);
        }
        UUID current = insert(account, scenario.staffUpgrade() ? actor : account, plan,
                new LearnerScenario.Dates(starts,dates.endsAt()),price,scenario.discount(),prior,scenario.staffUpgrade(),null);
        if (scenario.staffUpgrade()) {
            var detail = json.createObjectNode().put("fixture",true).put("pricePaidCents",0)
                    .put("replacesId",prior.toString()).put("planId",scenario.membership());
            jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,?,?,?::jsonb)",
                    UUID.randomUUID(),actor,"staff-upgrade",current.toString(),detail.toString());
        }
        if (scenario.scheduled() != null) {
            var next = definition(scenario.scheduled());
            Instant end = MembershipRules.end(dates.endsAt(),next.path("duration").path("kind").asText(),next.path("duration").path("count").asInt());
            insert(account,account,next,new LearnerScenario.Dates(dates.endsAt(),end),next.path("priceCents").asInt(),
                    null,current,false,null);
        }
        if (scenario.extraMembership() != null) {
            var extra = definition(scenario.extraMembership());
            insert(account,account,extra,scenario.dates(now,extra.path("duration").path("kind").asText(),
                    extra.path("duration").path("count").asInt()),extra.path("priceCents").asInt(),scenario.discount(),null,false,null);
        }
    }
    private ObjectNode definition(String id) {
        return jdbc.query("SELECT definition,price_cents FROM membership_plans WHERE id=?",(row,n)-> {
            var result=(ObjectNode)json.readTree(row.getString(1)); result.put("priceCents",row.getInt(2)); return result;
        },id).stream().findFirst().orElseThrow(()->new IllegalStateException("Scenario membership is unavailable"));
    }
    private UUID insert(UUID account, UUID actor, ObjectNode plan, LearnerScenario.Dates dates, int price,
                        String discountKind, UUID replaces, boolean staff, Instant superseded) {
        UUID id=UUID.randomUUID();
        // Discount qualification/rates are still undecided. Preserve the requested category
        // as explicit synthetic metadata; do not invent a commercial percentage or eligibility.
        String discount=discountKind==null?null:json.createObjectNode().put("kind",discountKind)
                .put("label",discountKind+" (synthetic, rate pending)").put("fixture",true).put("amountCents",0).put("note","Synthetic category; rate not configured").toString();
        String payment=staff?null:"pi_fixture_"+id;
        jdbc.update("""
            INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,
              price_paid_cents,discount,status,starts_at,ends_at,source,actor_id,provider,provider_payment_id,
              provider_status,replaces_id,superseded_at)
            VALUES (?,?,?,?::jsonb,?,?,?,?::jsonb,'succeeded',?,?,?,?,?,?,?,?,?)
            """,id,account,plan.path("id").asText(),plan.toString(),"0".repeat(64),"fixture-"+id,price,discount,
                Timestamp.from(dates.startsAt()),Timestamp.from(dates.endsAt()),staff?"staff-upgrade":"checkout",actor,
                "mock",payment,staff?"not-required":"succeeded",replaces,superseded==null?null:Timestamp.from(superseded));
        if(payment!=null) jdbc.update("INSERT INTO membership_mock_payments(idempotency_key,payment_id,amount_cents,currency,status) VALUES (?,?,?,'USD','succeeded')",
                "fixture-"+id,payment,price);
        return id;
    }
}
