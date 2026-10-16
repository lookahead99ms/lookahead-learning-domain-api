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

}
