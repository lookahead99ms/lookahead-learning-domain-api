package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970: the membership catalog as the data step {@code membership-catalog} seeds it (the ten V8 plan definitions
 * and prices of {@code membership_plans}), shared by the catalog step and the Local test fixture so both write the same
 * {@code CATALOG / PLAN#id} items. The definition JSON and the price are the plan's identity here; {@code updatedAt},
 * {@code revision} and {@code CATALOG / META} are the mutable catalog state the store owns afterwards.
 */
public final class MembershipCatalogSeed {
    private MembershipCatalogSeed() {}

    /** One seeded plan: its id, the definition JSON the API serves and the price in cents. */
    public record Plan(String id, String definition, long priceCents) {}

    public static final List<Plan> PLANS = List.of(
        new Plan("free", "{\"id\": \"free\", \"name\": \"Free\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"free\", \"scope\": {\"kind\": \"free\"}, \"duration\": {\"kind\": \"ongoing\"}, \"renewal\": \"not-applicable\", \"pending\": []}", 0),
        new Plan("learn", "{\"id\": \"learn\", \"name\": \"Learn\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"per-year\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\"]}, \"duration\": {\"kind\": \"years\", \"count\": 1}, \"renewal\": \"manual\", \"pending\": []}", 6999),
        new Plan("grow", "{\"id\": \"grow\", \"name\": \"Grow\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"per-year\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"grow\"]}, \"duration\": {\"kind\": \"years\", \"count\": 1}, \"renewal\": \"manual\", \"pending\": []}", 9999),
        new Plan("advance", "{\"id\": \"advance\", \"name\": \"Advance\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"per-year\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\", \"grow\"]}, \"duration\": {\"kind\": \"years\", \"count\": 1}, \"renewal\": \"manual\", \"pending\": []}", 14499),
        new Plan("complete", "{\"id\": \"complete\", \"name\": \"Complete\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"per-year\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\", \"grow\", \"blaze\"]}, \"duration\": {\"kind\": \"years\", \"count\": 1}, \"renewal\": \"manual\", \"pending\": []}", 27999),
        new Plan("course-pass", "{\"id\": \"course-pass\", \"name\": \"Course Pass\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"per-course\", \"scope\": {\"kind\": \"courses\", \"min\": 1, \"max\": 3}, \"duration\": {\"kind\": \"pending\"}, \"renewal\": \"manual\", \"pending\": [\"Duration\"]}", 2799),
        new Plan("interview-sprint", "{\"id\": \"interview-sprint\", \"name\": \"Interview Sprint\", \"group\": \"regular\", \"currency\": \"USD\", \"priceBasis\": \"one-time\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\", \"grow\", \"blaze\"]}, \"duration\": {\"kind\": \"days\", \"count\": 100}, \"renewal\": \"manual\", \"pending\": []}", 9999),
        new Plan("monthly-promo", "{\"id\": \"monthly-promo\", \"name\": \"Monthly Promo\", \"group\": \"promo\", \"currency\": \"USD\", \"priceBasis\": \"per-month\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\", \"grow\", \"blaze\"]}, \"duration\": {\"kind\": \"months\", \"count\": 1}, \"renewal\": \"manual\", \"pending\": []}", 2799),
        new Plan("annual-promo", "{\"id\": \"annual-promo\", \"name\": \"Annual Promo\", \"group\": \"promo\", \"currency\": \"USD\", \"priceBasis\": \"per-year\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\", \"grow\", \"blaze\"]}, \"duration\": {\"kind\": \"years\", \"count\": 1}, \"renewal\": \"manual\", \"pending\": []}", 10449),
        new Plan("interview-pass-promo", "{\"id\": \"interview-pass-promo\", \"name\": \"Interview Pass Promo\", \"group\": \"promo\", \"currency\": \"USD\", \"priceBasis\": \"one-time\", \"scope\": {\"kind\": \"catalogs\", \"catalogs\": [\"learn\", \"grow\", \"blaze\"]}, \"duration\": {\"kind\": \"days\", \"count\": 90}, \"renewal\": \"manual\", \"pending\": []}", 5999));

    public static List<String> ids() { return PLANS.stream().map(Plan::id).toList(); }

    /** The {@code CATALOG / PLAN#id} item of a plan at revision 1, as {@link MembershipItems#row} reads it. */
    public static Map<String, AttributeValue> planItem(ObjectMapper json, Plan plan, Instant at) {
        JsonNode definition = json.readTree(plan.definition());
        return DynamoItems.item(MembershipItems.CATALOG_PK, MembershipItems.planSk(plan.id()), MembershipItems.SCHEMA, Map.of(
                "id", DynamoItems.s(plan.id()), "definition", DynamoItems.json(definition), "priceCents", DynamoItems.n(plan.priceCents()),
                "updatedAt", DynamoItems.s(at.toString()), "revision", DynamoItems.n(1)));
    }
    /** The {@code CATALOG / META} revision controller at revision 1. */
    public static Map<String, AttributeValue> metaItem(Instant at) {
        return DynamoItems.item(MembershipItems.CATALOG_PK, MembershipItems.META_SK, MembershipItems.SCHEMA,
                Map.of("revision", DynamoItems.n(1), "createdAt", DynamoItems.s(at.toString())));
    }
}
