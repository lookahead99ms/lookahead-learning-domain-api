package com.lookahead.domain.membership;

import com.lookahead.domain.access.*;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipDatabaseTest {
    JdbcTemplate jdbc;DataSourceTransactionManager manager;String schema;boolean createdRole;
    StaffAccessService staff;Clock clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC);ObjectMapper json=new ObjectMapper();
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));schema="membership_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        createdRole=jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='lookahead_platform_app'",Integer.class)==0;if(createdRole)jdbc.execute("CREATE ROLE lookahead_platform_app NOLOGIN");
        jdbc.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");jdbc.execute("CREATE FUNCTION reject_author_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable'; END; $$");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V8__memberships.sql")));
        staff=mock(StaffAccessService.class);when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(true,Set.of()));
    }
    @AfterAll void cleanup(){if(jdbc!=null){jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(createdRole)jdbc.execute("DROP ROLE lookahead_platform_app");}}
    AccountPrincipal actor(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects VALUES (?)",id);return new AccountPrincipal(id,"synthetic","Synthetic",true,false);}
    MembershipService service(String scenario){return new MembershipService(jdbc,json,manager,new MockStripePaymentGateway(jdbc,manager,scenario),staff,clock);}
    MembershipService.Buy buy(String plan){return new MembershipService.Buy(plan,null,List.of());}
    @Test void replayAndConflictingKeyNeverDuplicatePayment(){var a=actor();var s=service("success");var first=s.buy(a,a.accountId(),buy("learn"),"request-001",false);assertThat(first.path("status").asText()).isEqualTo("succeeded");assertThat(s.buy(a,a.accountId(),buy("learn"),"request-001",false)).isEqualTo(first);assertThatThrownBy(()->s.buy(a,a.accountId(),buy("grow"),"request-001",false)).hasMessageContaining("different request");assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,a.accountId())).isEqualTo(1);}
    @Test void concurrentDuplicateCreatesOnePurchase() throws Exception {var a=actor();var s=service("success");try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var one=pool.submit(()->s.buy(a,a.accountId(),buy("learn"),"concurrent-1",false));var two=pool.submit(()->s.buy(a,a.accountId(),buy("learn"),"concurrent-1",false));assertThat(one.get().path("id").asText()).isEqualTo(two.get().path("id").asText());}assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,a.accountId())).isEqualTo(1);}
    @Test void timeoutAfterProviderSuccessRecoversSameCharge(){var a=actor();var p=service("timeout-after-success").buy(a,a.accountId(),buy("complete"),"timeout-001",false);assertThat(p.path("status").asText()).isEqualTo("succeeded");assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_payment_attempts WHERE purchase_id=?",Integer.class,UUID.fromString(p.path("id").asText()))).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_mock_payments WHERE payment_id=?",Integer.class,p.path("providerPaymentId").asText())).isEqualTo(1);}
    @Test void declineNeverRetriesOrGrants(){var a=actor();var s=service("decline");var p=s.buy(a,a.accountId(),buy("learn"),"declined-001",false);assertThat(p.path("status").asText()).isEqualTo("failed");assertThat(s.access(a,a.accountId())).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_payment_attempts WHERE purchase_id=?",Integer.class,UUID.fromString(p.path("id").asText()))).isEqualTo(1);}
    @Test void transientRetriesBoundedAcrossReplay(){for(String scenario:List.of("network","rate-limit","server-error")){var a=actor();var s=service(scenario);var p=s.buy(a,a.accountId(),buy("learn"),"transient-001",false);s.buy(a,a.accountId(),buy("learn"),"transient-001",false);assertThat(p.path("status").asText()).isEqualTo("pending");assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_payment_attempts WHERE purchase_id=?",Integer.class,UUID.fromString(p.path("id").asText()))).isEqualTo(3);assertThat(s.access(a,a.accountId())).isEmpty();}}
    @Test void requiresActionDoesNotGrant(){var a=actor();var s=service("requires-action");assertThat(s.buy(a,a.accountId(),buy("learn"),"action-001",false).path("providerStatus").asText()).isEqualTo("requires_action");assertThat(s.access(a,a.accountId())).isEmpty();}
    @Test void coursePassAndMissingKeyRejected(){var a=actor();var s=service("success");assertThatThrownBy(()->s.buy(a,a.accountId(),buy("course-pass"),"course-001",false)).hasMessageContaining("not purchasable");assertThatThrownBy(()->s.buy(a,a.accountId(),buy("learn"),null,false)).hasMessageContaining("Idempotency");}
    @Test void promoRequiresServerTimedSaleAndExpiresAtEnd(){var a=actor();var s=service("success");assertThatThrownBy(()->s.buy(a,a.accountId(),buy("monthly-promo"),"promo-001",false)).hasMessageContaining("sale");var receipt=s.startSale(a,new MembershipService.Sale("spring","Test",clock.instant().minusSeconds(1),clock.instant().plusSeconds(60),List.of("monthly-promo")));assertThat(s.buy(a,a.accountId(),buy("monthly-promo"),"promo-001",false).path("status").asText()).isEqualTo("succeeded");s.stopSale(a,UUID.fromString(receipt.path("id").asText()));assertThat(s.activeSale()).isNull();}
    @Test void staffUpgradeKeepsTermAndRejectsDowngrade(){var a=actor();var s=service("success");var first=s.buy(a,a.accountId(),buy("learn"),"staff-base",false);var next=s.buy(a,a.accountId(),new MembershipService.Buy("advance",UUID.fromString(first.path("id").asText()),List.of()),"staff-next",true);assertThat(next.path("pricePaidCents").asInt()).isZero();assertThat(next.path("endsAt").asText()).isEqualTo(first.path("endsAt").asText());assertThatThrownBy(()->s.buy(a,a.accountId(),new MembershipService.Buy("grow",UUID.fromString(next.path("id").asText()),List.of()),"staff-lower",true)).hasMessageContaining("cannot reduce");}
    @Test void earlyDowngradeRejectedAndFinalWeekSchedules(){var a=actor();var s=service("success");var first=s.buy(a,a.accountId(),buy("advance"),"change-base",false);UUID id=UUID.fromString(first.path("id").asText());assertThatThrownBy(()->s.buy(a,a.accountId(),new MembershipService.Buy("grow",id,List.of()),"change-next",false)).hasMessageContaining("final seven");Instant ends=Instant.parse(first.path("endsAt").asText());var later=new MembershipService(jdbc,json,manager,new MockStripePaymentGateway(jdbc,manager,"success"),staff,Clock.fixed(ends.minus(Duration.ofDays(7)),ZoneOffset.UTC));var scheduled=later.buy(a,a.accountId(),new MembershipService.Buy("grow",id,List.of()),"change-next",false);assertThat(scheduled.path("status").asText()).isEqualTo("scheduled");assertThat(scheduled.path("startsAt").asText()).isEqualTo(first.path("endsAt").asText());}
    @Test void dynamicCatalogGrantsAndOwnership(){var a=actor();var s=service("success");var p=s.buy(a,a.accountId(),buy("learn"),"grant-test",false);var catalog=mock(SnapshotValidator.class);when(catalog.allTopicIds()).thenReturn(Set.of("learn:java","grow:kafka"));var grants=new MembershipGrants(jdbc,catalog);assertThat(grants.topics(a.accountId())).containsExactly("learn:java");when(catalog.allTopicIds()).thenReturn(Set.of("learn:java","learn:new","grow:kafka"));assertThat(grants.topics(a.accountId())).containsExactlyInAnyOrder("learn:java","learn:new");assertThatThrownBy(()->s.get(actor(),UUID.fromString(p.path("id").asText()))).hasMessageContaining("not found");jdbc.update("UPDATE membership_purchases SET ends_at=now()-interval '1 second',starts_at=now()-interval '1 day' WHERE id=?",UUID.fromString(p.path("id").asText()));assertThat(grants.topics(a.accountId())).isEmpty();}
    @Test void roundingAndCalendarDuration(){Instant start=Instant.parse("2026-01-01T00:00:00Z"),end=start.plus(Duration.ofDays(100));assertThat(MembershipRules.prorateHalfUpCents(101,start,end,start.plus(Duration.ofDays(50)))).isEqualTo(51);assertThat(MembershipRules.end(Instant.parse("2026-01-31T12:00:00Z"),"months",1)).isEqualTo(Instant.parse("2026-02-28T12:00:00Z"));}
    @Test void renewalSchedulesFullTermAndRejectsSecondOrExpiredRenewal(){var a=actor();var s=service("success");var first=s.buy(a,a.accountId(),buy("learn"),"renew-base",false);UUID id=UUID.fromString(first.path("id").asText());var req=new MembershipService.Buy("learn",id,List.of());var q=s.quote(a,req);assertThat(q.path("startsAt").asText()).isEqualTo(first.path("endsAt").asText());var renewal=s.buy(a,a.accountId(),req,"renew-next",false);assertThat(renewal.path("status").asText()).isEqualTo("scheduled");assertThatThrownBy(()->s.buy(a,a.accountId(),req,"renew-again",false)).hasMessageContaining("already scheduled");jdbc.update("UPDATE membership_purchases SET ends_at=starts_at+interval '1 day' WHERE id=?",id);var future=new MembershipService(jdbc,json,manager,new MockStripePaymentGateway(jdbc,manager,"success"),staff,Clock.fixed(clock.instant().plus(Duration.ofDays(2)),ZoneOffset.UTC));assertThatThrownBy(()->future.quote(a,req)).hasMessageContaining("active purchase");}
    @Test void staleQuoteCannotBuyAtChangedPrice(){var a=actor();var s=service("success");assertThatThrownBy(()->s.buy(a,a.accountId(),new MembershipService.Buy("learn",null,List.of(),1),"stale-price",false)).hasMessageContaining("price changed");assertThat(s.access(a,a.accountId())).isEmpty();}
    @Test void learnerCannotReadOrUpgradeAnotherAccount(){var a=actor();var target=actor();when(staff.permissions(a)).thenReturn(StaffPermissions.forRoles(false,Set.of()));var s=service("success");assertThatThrownBy(()->s.access(a,target.accountId())).hasMessageContaining("Author, co-author or admin");assertThatThrownBy(()->s.buy(a,target.accountId(),buy("complete"),"forbidden-key",true)).hasMessageContaining("Author or admin");}
    @Test void coauthorCanReadPurchasesButCannotIncreaseAccess() {
        var learner=actor(); var coauthor=actor(); var s=service("success");
        var purchase=s.buy(learner,learner.accountId(),buy("learn"),"coauthor-read-base",false);
        when(staff.permissions(coauthor)).thenReturn(StaffPermissions.forRoles(false,Set.of("co-author")));
        assertThat(s.access(coauthor,learner.accountId())).hasSize(1);
        assertThatThrownBy(() -> s.buy(coauthor,learner.accountId(),
                new MembershipService.Buy("complete",UUID.fromString(purchase.path("id").asText()),List.of()),
                "coauthor-denied-upgrade",true)).hasMessageContaining("Author or admin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,
                learner.accountId())).isEqualTo(1);
    }


    // ---- DLV-969: the payment flow in the log (lookahead.payment), one line per attempt and one per outcome.

    static String purchaseId(ObjectNode purchase){return purchase.path("id").asText();}
    static java.util.List<String> attemptLines(com.lookahead.domain.LogCapture log){return log.lines("payment.attempt").stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();}

    @Test void aRetryableFailureThenSuccessIsLoggedAttemptByAttempt(){
        var a=actor();
        try(var log=com.lookahead.domain.LogCapture.start()){
            var p=service("timeout-after-success").buy(a,a.accountId(),buy("complete"),"log-retry-ok",false);String id=purchaseId(p);
            var attempts=attemptLines(log);
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(0)).matches("Payment attempt 1 of 3 for purchase "+id+" failed: timeout \\(retryable\\); retrying in \\d+ ms");
            assertThat(attempts.get(1)).isEqualTo("Payment for purchase "+id+" succeeded on attempt 2 of 3");
            var retry=log.lines("payment.attempt").getFirst();
            assertThat(retry.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(com.lookahead.domain.LogCapture.keys(retry)).containsEntry("retry.attempt",1).containsEntry("retry.max",3).containsEntry("reason","timeout")
                .containsEntry("purchase.id",UUID.fromString(id)).containsEntry("event.outcome","failure");
            var requested=log.only("purchase.requested");
            assertThat(requested.getFormattedMessage()).isEqualTo("Purchase "+id+" requested for account "+a.accountId()+" by account "+a.accountId()+": plan complete (new), price "+p.path("pricePaidCents").asInt()+" cents");
            var activated=log.only("membership.activated");
            assertThat(activated.getFormattedMessage()).isEqualTo("Membership activated for account "+a.accountId()+": plan complete until "+p.path("endsAt").asText()+" (purchase "+id+")");
            assertThat(log.lines()).allSatisfy(line->assertThat(line.getLevel()).isNotEqualTo(ch.qos.logback.classic.Level.ERROR));
            log.containsNone("synthetic","log-retry-ok");
        }
    }

    @Test void threeRetryableFailuresGiveUpWithAWarningAndARetryWithTheSameKeyDoesNotPayAgain(){
        var a=actor();var s=service("server-error");
        try(var log=com.lookahead.domain.LogCapture.start()){
            String id=purchaseId(s.buy(a,a.accountId(),buy("learn"),"log-retry-fail",false));
            var attempts=attemptLines(log);
            assertThat(attempts).hasSize(3);
            assertThat(attempts.get(0)).matches("Payment attempt 1 of 3 for purchase "+id+" failed: server-error \\(retryable\\); retrying in \\d+ ms");
            assertThat(attempts.get(1)).matches("Payment attempt 2 of 3 for purchase "+id+" failed: server-error \\(retryable\\); retrying in \\d+ ms");
            assertThat(attempts.get(2)).isEqualTo("Payment for purchase "+id+" failed after 3 attempts: server-error; purchase stays pending, no access granted");
            assertThat(log.lines("payment.attempt")).allSatisfy(line->assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
            assertThat(log.lines("membership.activated")).isEmpty();
            s.buy(a,a.accountId(),buy("learn"),"log-retry-fail",false);
            assertThat(log.only("purchase.reused").getFormattedMessage()).isEqualTo("Purchase "+id+" for account "+a.accountId()+" requested again with the same Idempotency-Key: the existing purchase is used");
            assertThat(attemptLines(log).getLast()).isEqualTo("Payment for purchase "+id+" not tried again: all 3 attempts were used earlier; purchase stays pending");
        }
    }

    @Test void aDeclineAndANonRetryableFailureAreLoggedOnceAndNeverRetried(){
        var a=actor();
        try(var log=com.lookahead.domain.LogCapture.start()){
            String declined=purchaseId(service("decline").buy(a,a.accountId(),buy("learn"),"log-decline",false));
            assertThat(attemptLines(log)).containsExactly("Payment for purchase "+declined+" declined by the provider on attempt 1 of 3 (requires_payment_method): not retried; purchase failed, no access granted");
            assertThat(com.lookahead.domain.LogCapture.keys(log.lines("payment.attempt").getFirst())).containsEntry("reason","DECLINED").containsEntry("event.outcome","refused");
        }
        var b=actor();
        PaymentGateway broken=new PaymentGateway(){
            public Result create(String key,int amount,String currency){throw new Failure("card-invalid",false);}
            public Result confirm(String id,String key){throw new UnsupportedOperationException();}
            public Result status(String id){throw new UnsupportedOperationException();}
        };
        try(var log=com.lookahead.domain.LogCapture.start()){
            var p=new MembershipService(jdbc,json,manager,broken,staff,clock).buy(b,b.accountId(),buy("learn"),"log-not-retryable",false);
            assertThat(p.path("status").asText()).isEqualTo("failed");
            assertThat(attemptLines(log)).containsExactly("Payment attempt 1 of 3 for purchase "+purchaseId(p)+" failed: card-invalid (not retryable); purchase failed, no access granted");
        }
        var c=actor();
        try(var log=com.lookahead.domain.LogCapture.start()){
            String pending=purchaseId(service("requires-action").buy(c,c.accountId(),buy("learn"),"log-action",false));
            assertThat(attemptLines(log)).containsExactly("Payment for purchase "+pending+" needs customer action on attempt 1 of 3 (requires_action); purchase stays pending, no access yet");
        }
    }

    @Test void aBusyPaymentSlotIsALimitLine() throws Exception {
        var a=actor();var b=actor();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        PaymentGateway slow=new PaymentGateway(){
            public Result create(String key,int amount,String currency){entered.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new Result("pi_slow_"+key,"succeeded",amount,currency);}
            public Result confirm(String id,String key){throw new UnsupportedOperationException();}
            public Result status(String id){throw new UnsupportedOperationException();}
        };
        var s=new MembershipService(jdbc,json,manager,slow,staff,clock);
        try(var log=com.lookahead.domain.LogCapture.start();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var first=pool.submit(()->s.buy(a,a.accountId(),buy("learn"),"log-busy-first",false));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->s.buy(b,b.accountId(),buy("learn"),"log-busy-second",false))
                .isInstanceOfSatisfying(com.lookahead.learning.content.exception.AccountFailure.class,f->assertThat(f.code()).isEqualTo("PAYMENT_BUSY"));
            release.countDown();first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var busy=log.lines("purchase.request").stream().filter(line->"PAYMENT_BUSY".equals(com.lookahead.domain.LogCapture.keys(line).get("reason"))).toList();
            assertThat(busy).singleElement().satisfies(line->{
                assertThat(line.getLoggerName()).isEqualTo("lookahead.limit");
                assertThat(line.getFormattedMessage()).isEqualTo("Payment busy for account "+b.accountId()+": one payment runs at a time and none finished within 3 s; refused with 429, retry with the same key");
                assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("limit.name","payment-slot").containsEntry("limit.max",1L).containsEntry("limit.window","3s");
            });
        }
    }

    @Test void membershipRuleRefusalsAndOwnerSaleActionsAreLogged(){
        var a=actor();var s=service("success");
        try(var log=com.lookahead.domain.LogCapture.start()){
            var first=s.buy(a,a.accountId(),buy("advance"),"log-change-base",false);
            assertThatThrownBy(()->s.buy(a,a.accountId(),new MembershipService.Buy("grow",UUID.fromString(purchaseId(first)),List.of()),"log-change-early",false)).hasMessageContaining("final seven");
            var refused=log.lines("purchase.request").getLast();
            assertThat(refused.getFormattedMessage()).isEqualTo("Purchase for account "+a.accountId()+" refused: CHANGE_WINDOW_CLOSED (Choose a different plan in the final seven days)");
            assertThat(com.lookahead.domain.LogCapture.keys(refused)).containsEntry("plan.code","grow").containsEntry("event.outcome","refused");
            var upgrade=s.buy(a,a.accountId(),new MembershipService.Buy("complete",UUID.fromString(purchaseId(first)),List.of()),"log-upgrade",false);
            assertThat(log.only("membership.upgraded").getFormattedMessage()).isEqualTo("Membership of account "+a.accountId()+" upgraded to plan complete until "
                +upgrade.path("endsAt").asText()+": purchase "+purchaseId(upgrade)+" replaces purchase "+purchaseId(first));
            assertThat(log.lines("purchase.requested").getLast().getFormattedMessage()).contains("plan complete (upgrade), price "+upgrade.path("pricePaidCents").asInt()+" cents, prorated from a difference of ");
            var sale=s.startSale(a,new MembershipService.Sale("spring","Private greeting",clock.instant().minusSeconds(1),clock.instant().plusSeconds(60),List.of("monthly-promo")));
            s.stopSale(a,UUID.fromString(sale.path("id").asText()));
            assertThat(log.only("sale.started").getFormattedMessage()).startsWith("Owner started sale "+sale.path("id").asText()+" (spring) for plan(s) [monthly-promo]");
            assertThat(log.only("sale.stopped").getFormattedMessage()).isEqualTo("Owner stopped sale "+sale.path("id").asText());
            int before=s.catalog().stream().filter(p->p.path("id").asText().equals("learn")).findFirst().orElseThrow().path("priceCents").asInt();
            s.price(a,"learn",before+100);
            assertThat(log.only("plan.price-changed").getFormattedMessage()).isEqualTo("Owner changed the price of plan learn from "+before+" to "+(before+100)+" cents");
            s.price(a,"learn",before);
            log.containsNone("Private greeting","synthetic");
        }
    }
}
