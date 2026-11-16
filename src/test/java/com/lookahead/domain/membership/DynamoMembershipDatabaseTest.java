package com.lookahead.domain.membership;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StaffPermissions;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.domain.testaccounts.TestAccountItems;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lookahead.domain.membership.DynamoLocalFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The membership flows of {@link MembershipDatabaseTest} on DynamoDB Local through {@link DynamoMembershipStore}. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoMembershipDatabaseTest {
    private static DynamoDbClient client;
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private StaffAccessService staff;

    @BeforeAll static void connect() { client = connect0(); ensureTables(client); }
    private static DynamoDbClient connect0() { return DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void seed() {
        purgeLaneC(client);
        seedCatalog(client, json, NOW);
        staff = mock(StaffAccessService.class);
        when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(true, Set.of()));
    }
    AccountPrincipal actor() { UUID id = UUID.randomUUID(); seedAccount(client, id, NOW); return new AccountPrincipal(id, "synthetic", "Synthetic", true, false); }
    DynamoMembershipStore store(Clock at) { return new DynamoMembershipStore(client, TABLES, json, at); }
    MembershipService service(String scenario) { return service(scenario, clock); }
    MembershipService service(String scenario, Clock at) {
        return new MembershipService(store(at), json, new MockStripePaymentGateway(new DynamoMockPaymentStore(client, TABLES), scenario), staff, at);
    }
    MembershipService.Buy buy(String plan) { return new MembershipService.Buy(plan, null, List.of()); }
    static UUID id(ObjectNode p) { return UUID.fromString(p.path("id").asText()); }
    ObjectNode stored(UUID account, UUID purchase) { return MembershipItems.row(item(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(account), MembershipItems.purchaseSk(purchase)), json); }
    static AccountFailure failure(ThrowableAssert.ThrowingCallable call) {
        return (AccountFailure) catchThrowableOfType(AccountFailure.class, call);
    }

    @Test void replayAndConflictingKeyNeverDuplicatePaymentAndRowsCarryRevisions() {
        var a = actor(); var s = service("success");
        var first = s.buy(a, a.accountId(), buy("learn"), "request-001", false);
        assertThat(first.path("status").asText()).isEqualTo("succeeded");
        assertThat(first.has("fingerprint")).isFalse();
        assertThat(s.buy(a, a.accountId(), buy("learn"), "request-001", false)).isEqualTo(first);
        assertThat(failure(() -> s.buy(a, a.accountId(), buy("grow"), "request-001", false)).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(MembershipItems.purchases(client, TABLES, json, a.accountId())).hasSize(1);
        var row = stored(a.accountId(), id(first));
        assertThat(row.path("revision").asLong()).isEqualTo(2);           // inserted pending, settled once
        assertThat(row.path("paymentAttempts")).hasSize(1);
        assertThat(row.path("idempotencyKey").asText()).isEqualTo("request-001");
        assertThat(item(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(a.accountId()), "META").get("revision").n()).isEqualTo("3");
        assertThat(item(client, TABLES.userAccountDetailsTableName(), MembershipItems.purchasePointerPk(id(first)), "ACCOUNT").get("accountId").s()).isEqualTo(a.accountId().toString());
        assertThat(item(client, TABLES.userAccountDetailsTableName(), MembershipItems.providerEventPk("mock", first.path("providerPaymentId").asText() + ":succeeded"), "RECEIPT")).isNotEmpty();
        var audit = MembershipItems.auditEvents(client, TABLES, json, first.path("id").asText());
        assertThat(audit).extracting(e -> e.path("action").asText()).containsExactlyInAnyOrder("purchase-created", "purchase-succeeded");
        assertThat(s.get(a, id(first))).isEqualTo(first);
        assertThat(failure(() -> s.get(actor(), id(first))).code()).isEqualTo("PURCHASE_NOT_FOUND");
        assertThat(failure(() -> s.buy(a, a.accountId(), buy("grow"), "another-plan", false)).code()).isEqualTo("EXISTING_MEMBERSHIP");
    }

    @Test void concurrentDuplicateCreatesOnePurchase() throws Exception {
        var a = actor(); var s = service("success");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> s.buy(a, a.accountId(), buy("learn"), "concurrent-1", false));
            var two = pool.submit(() -> s.buy(a, a.accountId(), buy("learn"), "concurrent-1", false));
            assertThat(one.get().path("id").asText()).isEqualTo(two.get().path("id").asText());
        }
        assertThat(MembershipItems.purchases(client, TABLES, json, a.accountId())).hasSize(1);
    }

    @Test void providerOutcomesMatchPostgres() {
        var a = actor();
        var p = service("timeout-after-success").buy(a, a.accountId(), buy("complete"), "timeout-001", false);
        assertThat(p.path("status").asText()).isEqualTo("succeeded");
        assertThat(stored(a.accountId(), id(p)).path("paymentAttempts")).hasSize(2);
        assertThat(new DynamoMockPaymentStore(client, TABLES).findById(p.path("providerPaymentId").asText()).amountCents()).isEqualTo(27999);
        var b = actor(); var declined = service("decline").buy(b, b.accountId(), buy("learn"), "declined-001", false);
        assertThat(declined.path("status").asText()).isEqualTo("failed");
        assertThat(service("decline").access(b, b.accountId())).isEmpty();
        assertThat(stored(b.accountId(), id(declined)).path("paymentAttempts")).hasSize(1);
        for (String scenario : List.of("network", "rate-limit", "server-error")) {
            var c = actor(); var s = service(scenario);
            var pending = s.buy(c, c.accountId(), buy("learn"), "transient-001", false);
            s.buy(c, c.accountId(), buy("learn"), "transient-001", false);
            assertThat(pending.path("status").asText()).isEqualTo("pending");
            assertThat(stored(c.accountId(), id(pending)).path("paymentAttempts")).hasSize(3);
            assertThat(s.access(c, c.accountId())).isEmpty();
            assertThat(failure(() -> service("success").buy(c, c.accountId(), buy("grow"), "second-key", false)).code()).isEqualTo("PURCHASE_PENDING");
        }
        var d = actor(); var s = service("requires-action");
        assertThat(s.buy(d, d.accountId(), buy("learn"), "action-001", false).path("providerStatus").asText()).isEqualTo("requires_action");
        assertThat(s.access(d, d.accountId())).isEmpty();
        var e = actor();
        assertThat(failure(() -> s.buy(e, e.accountId(), buy("course-pass"), "course-001", false)).getMessage()).contains("not purchasable");
        assertThat(failure(() -> s.buy(e, e.accountId(), buy("learn"), null, false)).getMessage()).contains("Idempotency");
        assertThat(failure(() -> s.buy(e, e.accountId(), buy("nope"), "unknown-plan", false)).getMessage()).contains("Unknown membership plan");
        UUID ghost = UUID.randomUUID();
        assertThat(failure(() -> service("success").buy(new AccountPrincipal(ghost, "g", "G", true), ghost, buy("learn"), "no-subject", false)).code()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test void promoRequiresServerTimedSaleAndSalesNeverOverlap() {
        var a = actor(); var s = service("success");
        assertThat(failure(() -> s.buy(a, a.accountId(), buy("monthly-promo"), "promo-001", false)).code()).isEqualTo("SALE_INACTIVE");
        assertThat(s.catalog().stream().filter(p -> p.path("id").asText().equals("monthly-promo")).findFirst().orElseThrow().path("onSale").asBoolean()).isFalse();
        var receipt = s.startSale(a, new MembershipService.Sale("spring", "Test", NOW.minusSeconds(1), NOW.plusSeconds(60), List.of("monthly-promo")));
        assertThat(s.activeSale().path("id").asText()).isEqualTo(receipt.path("id").asText());
        assertThat(s.activeSale().path("planIds").get(0).asText()).isEqualTo("monthly-promo");
        assertThat(failure(() -> s.startSale(a, new MembershipService.Sale("holiday", "Again", NOW.plusSeconds(30), NOW.plusSeconds(90), List.of("annual-promo")))).code()).isEqualTo("SALE_OVERLAP");
        assertThat(failure(() -> s.startSale(a, new MembershipService.Sale("holiday", "Again", NOW, NOW.plusSeconds(90), List.of("learn")))).getMessage()).contains("promo");
        assertThat(s.catalog().stream().filter(p -> p.path("id").asText().equals("monthly-promo")).findFirst().orElseThrow().path("onSale").asBoolean()).isTrue();
        assertThat(s.buy(a, a.accountId(), buy("monthly-promo"), "promo-001", false).path("status").asText()).isEqualTo("succeeded");
        UUID sale = UUID.fromString(receipt.path("id").asText());
        s.stopSale(a, sale);
        assertThat(s.activeSale()).isNull();
        s.stopSale(a, sale); // idempotent, keeps the first stop
        assertThat(failure(() -> s.stopSale(a, UUID.randomUUID())).code()).isEqualTo("SALE_NOT_FOUND");
        // A later sale after the stopped one is allowed again.
        s.startSale(a, new MembershipService.Sale("holiday", "Later", NOW.plusSeconds(30), NOW.plusSeconds(90), List.of("annual-promo")));
        assertThat(item(client, TABLES.userAccountDetailsTableName(), "SALES", "META").get("revision").n()).isEqualTo("2");
        assertThat(MembershipItems.auditEvents(client, TABLES, json, sale.toString())).extracting(e -> e.path("action").asText()).containsExactlyInAnyOrder("sale-started", "sale-stopped", "sale-stopped");
    }

    @Test void staffUpgradeRenewalChangeAndProrationFollowTheRules() {
        var a = actor(); var s = service("success");
        var first = s.buy(a, a.accountId(), buy("learn"), "staff-base", false);
        var next = s.buy(a, a.accountId(), new MembershipService.Buy("advance", id(first), List.of()), "staff-next", true);
        assertThat(next.path("pricePaidCents").asInt()).isZero();
        assertThat(next.path("endsAt").asText()).isEqualTo(first.path("endsAt").asText());
        assertThat(s.get(a, id(first)).path("supersededAt").asText()).isEqualTo(NOW.toString());
        assertThat(failure(() -> s.buy(a, a.accountId(), new MembershipService.Buy("grow", id(next), List.of()), "staff-lower", true)).code()).isEqualTo("STAFF_DOWNGRADE_REJECTED");
        // change window and scheduling
        var b = actor();
        var base = s.buy(b, b.accountId(), buy("advance"), "change-base", false);
        assertThat(failure(() -> s.buy(b, b.accountId(), new MembershipService.Buy("grow", id(base), List.of()), "change-next", false)).code()).isEqualTo("CHANGE_WINDOW_CLOSED");
        Instant ends = Instant.parse(base.path("endsAt").asText());
        var later = service("success", Clock.fixed(ends.minus(Duration.ofDays(7)), ZoneOffset.UTC));
        var scheduled = later.buy(b, b.accountId(), new MembershipService.Buy("grow", id(base), List.of()), "change-next", false);
        assertThat(scheduled.path("status").asText()).isEqualTo("scheduled");
        assertThat(scheduled.path("startsAt").asText()).isEqualTo(base.path("endsAt").asText());
        assertThat(later.access(b, b.accountId())).extracting(p -> p.path("status").asText()).containsExactly("scheduled", "active");
        assertThat(failure(() -> later.buy(b, b.accountId(), new MembershipService.Buy("complete", id(base), List.of()), "change-again", false)).code()).isEqualTo("CHANGE_ALREADY_SCHEDULED");
        // renewal of the same plan, and the upgrade proration
        var c = actor();
        var learn = s.buy(c, c.accountId(), buy("learn"), "renew-base", false);
        var req = new MembershipService.Buy("learn", id(learn), List.of());
        assertThat(s.quote(c, req).path("startsAt").asText()).isEqualTo(learn.path("endsAt").asText());
        assertThat(s.buy(c, c.accountId(), req, "renew-next", false).path("status").asText()).isEqualTo("scheduled");
        assertThat(failure(() -> s.quote(c, req)).code()).isEqualTo("CHANGE_ALREADY_SCHEDULED");
        var d = actor();
        var paid = s.buy(d, d.accountId(), buy("learn"), "upgrade-base", false);
        var half = service("success", Clock.fixed(NOW.plus(Duration.ofDays(182)), ZoneOffset.UTC));
        var quote = half.quote(d, new MembershipService.Buy("advance", id(paid), List.of()));
        assertThat(quote.path("priceCents").asInt()).isBetween(3700, 3800);
        assertThat(failure(() -> half.buy(d, d.accountId(), new MembershipService.Buy("advance", id(paid), List.of(), 1), "stale-price", false)).code()).isEqualTo("PRICE_CHANGED");
        var upgraded = half.buy(d, d.accountId(), new MembershipService.Buy("advance", id(paid), List.of(), quote.path("priceCents").asInt()), "upgrade-next", false);
        assertThat(upgraded.path("endsAt").asText()).isEqualTo(paid.path("endsAt").asText());
        assertThat(half.access(d, d.accountId())).extracting(p -> p.path("status").asText()).containsExactly("active", "expired");
    }

    @Test void grantsSummaryAndOwnerPriceChangeUseTheStoredRows() {
        var a = actor(); var s = service("success");
        s.buy(a, a.accountId(), buy("learn"), "grant-test", false);
        var catalog = mock(SnapshotValidator.class);
        when(catalog.allTopicIds()).thenReturn(Set.of("learn:java", "grow:kafka"));
        var grants = new DynamoMembershipGrants(client, TABLES, json, catalog, clock);
        assertThat(grants.topics(a.accountId())).containsExactly("learn:java");
        when(catalog.allTopicIds()).thenReturn(Set.of("learn:java", "learn:new", "grow:kafka"));
        assertThat(grants.topics(a.accountId())).containsExactlyInAnyOrder("learn:java", "learn:new");
        assertThat(new DynamoMembershipGrants(client, TABLES, json, catalog, Clock.fixed(NOW.plus(Duration.ofDays(400)), ZoneOffset.UTC)).topics(a.accountId())).isEmpty();
        AccountGrantsLookup lookup = (account, now) -> Set.of("grow:api-design", "staff:documents");
        var summaries = new AccessSummaryService(new DynamoAccessSummaryStore(client, TABLES, json, lookup), staff, clock);
        assertThat(summaries.get(a, a.accountId()).label()).isEqualTo("Granted · grow:api-design + Regular · Learn");
        assertThat(new AccessSummaryService(new DynamoAccessSummaryStore(client, TABLES, json, (AccountGrantsLookup) null), staff, clock).get(a, a.accountId()).labels()).containsExactly("Regular · Learn");
        int before = s.catalog().stream().filter(p -> p.path("id").asText().equals("learn")).findFirst().orElseThrow().path("priceCents").asInt();
        assertThat(s.price(a, "learn", before + 100).path("priceCents").asInt()).isEqualTo(before + 100);
        assertThat(s.catalog()).extracting(p -> p.path("id").asText()).startsWith("free", "course-pass", "monthly-promo", "interview-pass-promo");
        assertThat(item(client, TABLES.userAccountDetailsTableName(), "CATALOG", "META").get("revision").n()).isEqualTo("2");
        assertThat(item(client, TABLES.userAccountDetailsTableName(), "CATALOG", "PLAN#learn").get("revision").n()).isEqualTo("2");
        assertThat(MembershipItems.auditEvents(client, TABLES, json, "learn")).extracting(e -> e.path("action").asText()).containsExactly("price-changed");
        assertThat(failure(() -> s.price(a, "nope", 100)).getMessage()).contains("Unknown membership plan");
        assertThat(failure(() -> s.price(a, "free", 100)).getMessage()).contains("Invalid price");
    }

    @Test void aPriceChangeDuringAPurchaseIsFencedByTheCatalogRevisionAndTheBodyIsRerun() {
        var a = actor(); var store = store(clock);
        var attempts = new AtomicInteger();
        UUID purchase = UUID.randomUUID();
        store.transact(a.accountId(), work -> {
            work.lockAccount(a.accountId());
            var plan = work.definition("learn");
            if (attempts.incrementAndGet() == 1) store.changePrice(a.accountId(), "learn", 7999);
            work.insertPurchase(new MembershipStore.NewPurchase(purchase, a.accountId(), "learn", plan, "f", "key", plan.path("priceCents").asInt(), NOW, NOW.plusSeconds(10), "checkout", a.accountId(), null));
            return null;
        });
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(store.purchase(a.accountId(), purchase).path("pricePaidCents").asInt()).isEqualTo(7999);
        // A writer that keeps losing surfaces the conflict after the bounded retries.
        var always = new AtomicInteger();
        assertThatThrownBy(() -> store.transact(a.accountId(), work -> {
            work.lockAccount(a.accountId());
            always.incrementAndGet();
            // Another writer moves META to a revision the snapshot did not read, every time.
            client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(DynamoItems.account(a.accountId()), "META", 1,
                    Map.of("revision", DynamoItems.n(100 + always.get())))).build());
            work.audit(a.accountId(), "noise", purchase.toString(), json.createObjectNode());
            return null;
        })).isInstanceOf(DynamoWrites.Conflict.class);
        assertThat(always.get()).isEqualTo(DynamoMembershipStore.ATTEMPTS);
        // Read-only bodies commit nothing.
        assertThat(store.<String>transact(a.accountId(), work -> { work.lockAccount(a.accountId()); return "read"; })).isEqualTo("read");
    }

    @Test void devRequestsWaitForTheOwnerAndApprovalPaysOnceUnderTheKey() {
        var a = actor();
        // A staff-created DEV test account on the owner's domain.
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(DynamoItems.account(a.accountId()), TestAccountItems.REGISTRY_SK, 1, Map.of(
                "accountId", DynamoItems.s(a.accountId().toString()), "username", DynamoItems.s("learner05@lookahead.dev"), "creationState", DynamoItems.s("active"),
                "initialization", DynamoItems.s("scenario"), "creatorId", DynamoItems.s(UUID.randomUUID().toString()), "revision", DynamoItems.n(1)))).build());
        var gateway = new OwnerApprovalPaymentGateway(new MockStripePaymentGateway(new DynamoMockPaymentStore(client, TABLES), "success"));
        var s = new MembershipService(store(clock), json, gateway, staff, clock);
        var outsider = actor();
        assertThat(failure(() -> s.quote(outsider, buy("learn"))).code()).isEqualTo("PURCHASES_TEST_ACCOUNTS_ONLY");
        assertThat(s.quote(a, buy("learn")).path("ownerApproval").asBoolean()).isTrue();
        UUID holder = UUID.randomUUID();
        var request = s.buy(a, a.accountId(), buy("learn"), "dev-request", false, holder);
        assertThat(request.path("status").asText()).isEqualTo("pending");
        assertThat(request.path("providerStatus").asText()).isEqualTo(OwnerApprovalPaymentGateway.AWAITING);
        assertThat(s.buy(a, a.accountId(), buy("learn"), "dev-request", false, holder)).isEqualTo(request);
        assertThat(failure(() -> s.buy(a, a.accountId(), buy("grow"), "dev-second", false, holder)).code()).isEqualTo("PURCHASE_REQUEST_PENDING");
        var pending = s.pendingRequest(a.accountId());
        assertThat(pending.requestedBy()).isEqualTo(holder);
        assertThat(pending.body().path("kind").asText()).isEqualTo("new");
        assertThat(pending.body().path("toPlan").path("name").asText()).isEqualTo("Learn");
        assertThat(pending.body().path("requestedAt").asText()).isEqualTo(NOW.toString());
        assertThat(s.approvedRequest(a.accountId())).isNull();
        var admin = actor();
        assertThat(failure(() -> s.decide(admin, new MembershipService.Decision(id(request), "approve"), "learner", t -> true)).code()).isEqualTo("PURCHASE_APPROVAL_FORBIDDEN");
        assertThat(failure(() -> s.decide(admin, new MembershipService.Decision(id(request), "approve"), "admin", t -> false)).code()).isEqualTo("PURCHASE_APPROVAL_FORBIDDEN");
        assertThat(failure(() -> s.decide(admin, new MembershipService.Decision(UUID.randomUUID(), "approve"), "admin", t -> true)).code()).isEqualTo("PURCHASE_NOT_FOUND");
        var approved = s.decide(admin, new MembershipService.Decision(id(request), "approve"), "admin", t -> true);
        assertThat(approved.path("purchase").path("status").asText()).isEqualTo("succeeded");
        assertThat(approved.path("repeated").asBoolean()).isFalse();
        assertThat(s.decide(admin, new MembershipService.Decision(id(request), "approve"), "admin", t -> true).path("repeated").asBoolean()).isTrue();
        assertThat(failure(() -> s.decide(admin, new MembershipService.Decision(id(request), "decline"), "admin", t -> true)).code()).isEqualTo("PURCHASE_REQUEST_DECIDED");
        assertThat(s.pendingRequest(a.accountId())).isNull();
        var marker = s.approvedRequest(a.accountId());
        assertThat(marker.requestedBy()).isEqualTo(holder);
        assertThat(marker.approvedBy()).isEqualTo(admin.accountId());
        assertThat(marker.kind()).isEqualTo("new");
        assertThat(marker.planName()).isEqualTo("Learn");
        assertThat(new DynamoMockPaymentStore(client, TABLES).findByKey("membership-" + id(request)).amountCents()).isEqualTo(6999);
        // A reset after the approval clears the marker; a decline fails a later request without paying.
        client.putItem(PutItemRequest.builder().tableName(TABLES.administratorAuditEventsTableName())
                .item(TestAccountItems.resetItem(a.accountId(), UUID.randomUUID(), admin.accountId(), "scenario", NOW.plusSeconds(1))).build());
        assertThat(s.approvedRequest(a.accountId())).isNull();
        var renewal = s.buy(a, a.accountId(), new MembershipService.Buy("learn", id(request), List.of()), "dev-renewal", false, holder);
        assertThat(s.pendingRequest(a.accountId()).body().path("kind").asText()).isEqualTo("renewal");
        var declined = s.decide(admin, new MembershipService.Decision(id(renewal), "decline"), "owner", t -> true);
        assertThat(declined.path("purchase").path("status").asText()).isEqualTo("failed");
        assertThat(declined.path("purchase").path("providerStatus").asText()).isEqualTo(OwnerApprovalPaymentGateway.DECLINED);
        assertThat(new DynamoMockPaymentStore(client, TABLES).findByKey("membership-" + id(renewal))).isNull();
        assertThat(s.decide(admin, new MembershipService.Decision(id(request), "approve"), "admin", t -> true).path("repeated").asBoolean()).isTrue();
        assertThat(failure(() -> s.decide(admin, new MembershipService.Decision(id(renewal), "approve"), "admin", t -> true)).code()).isEqualTo("PURCHASE_REQUEST_DECIDED");
    }

    @Test void aRequestSupersededByAResetIsStaleForApprovalAndMayStillBeDeclined() {
        var a = actor();
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(DynamoItems.account(a.accountId()), TestAccountItems.REGISTRY_SK, 1, Map.of(
                "accountId", DynamoItems.s(a.accountId().toString()), "username", DynamoItems.s("learner07@lookahead.dev"), "creationState", DynamoItems.s("active"),
                "initialization", DynamoItems.s("scenario"), "creatorId", DynamoItems.s(UUID.randomUUID().toString()), "revision", DynamoItems.n(1)))).build());
        var s = new MembershipService(store(clock), json, new OwnerApprovalPaymentGateway(new MockStripePaymentGateway(new DynamoMockPaymentStore(client, TABLES), "success")), staff, clock);
        UUID holder = UUID.randomUUID();
        var request = s.buy(a, a.accountId(), buy("learn"), "stale-request", false, holder);
        assertThat(request.path("status").asText()).isEqualTo("pending");
        // A Reset supersedes the account's purchases (TestAccountsService.supersedePurchases) before anyone decides.
        var row = stored(a.accountId(), id(request));
        row.put("supersededAt", NOW.toString());
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(MembershipItems.item(DynamoItems.account(a.accountId()), MembershipItems.purchaseSk(id(request)), row, MembershipItems.revision(row))).build());
        assertThat(s.pendingRequest(a.accountId())).as("it left the list").isNull();
        var admin = actor();
        var refused = failure(() -> s.decide(admin, new MembershipService.Decision(id(request), "approve"), "owner", t -> true));
        assertThat(refused.status()).isEqualTo(409); assertThat(refused.code()).isEqualTo("PURCHASE_REQUEST_STALE");
        assertThat(new DynamoMockPaymentStore(client, TABLES).findByKey("membership-" + id(request))).as("nothing paid").isNull();
        assertThat(MembershipItems.auditEvents(client, TABLES, json, id(request).toString())).extracting(e -> e.path("action").asText()).containsExactly("purchase-created");
        // It blocks nothing and may still be declined.
        assertThat(s.buy(a, a.accountId(), buy("learn"), "after-reset", false, holder).path("status").asText()).isEqualTo("pending");
        var declined = s.decide(admin, new MembershipService.Decision(id(request), "decline"), "owner", t -> true);
        assertThat(declined.path("purchase").path("status").asText()).isEqualTo("failed");
        assertThat(declined.path("purchase").path("providerStatus").asText()).isEqualTo(OwnerApprovalPaymentGateway.DECLINED);
        // An upgrade request whose replaced purchase is no longer active is stale the same way.
        var b = actor();
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(DynamoItems.account(b.accountId()), TestAccountItems.REGISTRY_SK, 1, Map.of(
                "accountId", DynamoItems.s(b.accountId().toString()), "username", DynamoItems.s("learner08@lookahead.dev"), "creationState", DynamoItems.s("active"),
                "initialization", DynamoItems.s("scenario"), "creatorId", DynamoItems.s(UUID.randomUUID().toString()), "revision", DynamoItems.n(1)))).build());
        var learn = service("success").buy(b, b.accountId(), buy("learn"), "upgrade-base", false);
        var upgrade = s.buy(b, b.accountId(), new MembershipService.Buy("advance", id(learn), List.of()), "upgrade-request", false, holder);
        var base = stored(b.accountId(), id(learn));
        base.put("supersededAt", NOW.toString());
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(MembershipItems.item(DynamoItems.account(b.accountId()), MembershipItems.purchaseSk(id(learn)), base, MembershipItems.revision(base))).build());
        assertThat(failure(() -> s.decide(admin, new MembershipService.Decision(id(upgrade), "approve"), "admin", t -> true)).code()).isEqualTo("PURCHASE_REQUEST_STALE");
    }

    @Test void aPurchaseSettledFirstByAConcurrentRetryIsActivatedOnceInTheLog() {
        var a = actor();
        var spy = mock(DynamoDbClient.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY).defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(client)));
        var mine = new MembershipService(new DynamoMembershipStore(spy, TABLES, json, clock), json, new MockStripePaymentGateway(new DynamoMockPaymentStore(client, TABLES), "success"), staff, clock);
        var rival = service("success");
        var raced = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            ExecuteTransactionRequest request = call.getArgument(0);
            boolean settling = request.transactStatements().stream().flatMap(st -> st.parameters().stream()).map(software.amazon.awssdk.services.dynamodb.model.AttributeValue::s)
                    .anyMatch(v -> v != null && v.startsWith("PROVIDEREVENT#"));
            // The same request, retried on another instance, settles the purchase before this instance's settle commits.
            if (settling && raced.compareAndSet(false, true)) rival.buy(a, a.accountId(), buy("learn"), "race-key", false);
            return client.executeTransaction(request);
        }).when(spy).executeTransaction(any(ExecuteTransactionRequest.class));
        ObjectNode done;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            done = mine.buy(a, a.accountId(), buy("learn"), "race-key", false);
            assertThat(raced.get()).isTrue();
            assertThat(done.path("status").asText()).isEqualTo("succeeded");
            assertThat(log.lines("membership.activated")).as("the run whose commit lost found it settled and logged no activation of its own").hasSize(1);
            assertThat(log.lines("purchase.requested")).hasSize(1);
            assertThat(log.lines("purchase.reused")).hasSize(1);
        }
        assertThat(MembershipItems.purchases(client, TABLES, json, a.accountId())).hasSize(1);
        assertThat(stored(a.accountId(), id(done)).path("paymentAttempts")).as("the attempt of the run that committed").hasSize(1);
        assertThat(new DynamoMockPaymentStore(client, TABLES).findByKey("membership-" + id(done)).amountCents()).isEqualTo(6999);
    }
}
