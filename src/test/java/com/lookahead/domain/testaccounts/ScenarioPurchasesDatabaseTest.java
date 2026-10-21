package com.lookahead.domain.testaccounts;

import com.lookahead.domain.membership.MembershipService;
import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.domain.membership.MockStripePaymentGateway;
import com.lookahead.domain.access.*;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioPurchasesDatabaseTest {
    JdbcTemplate jdbc; DataSourceTransactionManager manager; String schema; boolean createdRole;
    ObjectMapper json=new ObjectMapper();
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));schema="membership_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        createdRole=jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='lookahead_platform_app'",Integer.class)==0;if(createdRole)jdbc.execute("CREATE ROLE lookahead_platform_app NOLOGIN");
        jdbc.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");jdbc.execute("CREATE FUNCTION reject_author_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable'; END; $$");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V8__memberships.sql")));
        jdbc.execute("CREATE TABLE account_grants(account_id uuid,topic_id text,valid_until timestamptz)");
        jdbc.execute("CREATE TABLE staff_access(account_id uuid,topic_grants jsonb)");

    }
    @AfterAll void cleanup(){if(jdbc!=null){jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(createdRole)jdbc.execute("DROP ROLE lookahead_platform_app");}}

    @Test void all27ScenariosUseNormalAccessRecordsWithScheduledUpgradesAndPreservedAudit() {
        Instant now=Instant.parse("2026-10-15T12:00:00Z");
        UUID operator=UUID.randomUUID(); jdbc.update("INSERT INTO platform_subjects VALUES (?)",operator);
        var staff=mock(StaffAccessService.class);
        when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(true,Set.of()));
        var service=new MembershipService(jdbc,json,manager,new MockStripePaymentGateway(jdbc,manager,"success"),staff,Clock.fixed(now,ZoneOffset.UTC));
        var fixtures=new ScenarioPurchases(jdbc,json,new TestAccountPolicy(TestAccountPolicyTest.local()));
        for(var scenario:LearnerScenario.all()) {
            UUID id=TestAccountRoster.entries().get(scenario.number()-1).accountId();
            jdbc.update("INSERT INTO platform_subjects VALUES (?)",id);
            new TransactionTemplate(manager).executeWithoutResult(tx->fixtures.populate(id,operator,scenario,now));
            var actor=new AccountPrincipal(id,scenario.username(),"Synthetic",true,false);
            var records=service.access(actor,id);
            var summary=new AccessSummaryService(jdbc,json,staff,Clock.fixed(now,ZoneOffset.UTC)).get(actor,id);
            if(scenario.membership()==null||scenario.term()==LearnerScenario.Term.EXPIRED) assertThat(summary.label()).isEqualTo("Free");
            else assertThat(summary.label()).contains(scenario.membership().contains("promo")?"Promo · ":"Regular · ");
            if(scenario.membership()==null){assertThat(records).isEmpty();continue;}
            var primary=records.stream().filter(p->p.path("planId").asText().equals(scenario.membership())).findFirst().orElseThrow();
            assertThat(primary.path("status").asText()).isEqualTo(scenario.term()==LearnerScenario.Term.EXPIRED?"expired":"active");
            assertThat(primary.path("discount").isNull()).isEqualTo(scenario.discount()==null);
            if(scenario.discount()!=null) assertThat(primary.path("discount").path("kind").asText()).isEqualTo(scenario.discount());
            if(scenario.scheduled()!=null) {
                var scheduled=records.stream().filter(p->p.path("planId").asText().equals(scenario.scheduled())).findFirst().orElseThrow();
                assertThat(scheduled.path("status").asText()).isEqualTo("scheduled");
                assertThat(scheduled.path("startsAt")).isEqualTo(primary.path("endsAt"));
            }
            if(scenario.upgradedFrom()!=null) {
                var old=records.stream().filter(p->p.path("planId").asText().equals(scenario.upgradedFrom())).findFirst().orElseThrow();
                assertThat(old.path("status").asText()).isEqualTo("expired");
                assertThat(primary.path("endsAt")).isEqualTo(old.path("endsAt"));
                assertThat(primary.path("replacesId")).isEqualTo(old.path("id"));
                if(scenario.staffUpgrade()) {
                    assertThat(primary.path("pricePaidCents").asInt()).isZero();
                    assertThat(primary.path("source").asText()).isEqualTo("staff-upgrade");
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_audit WHERE subject_id=? AND actor_id=?",Integer.class,primary.path("id").asText(),operator)).isEqualTo(1);
                } else assertThat(primary.path("pricePaidCents").asInt()).isPositive().isLessThan(primary.path("plan").path("priceCents").asInt());
            }
            if(scenario.extraMembership()!=null) assertThat(records.stream().filter(p->p.path("status").asText().equals(scenario.term()==LearnerScenario.Term.EXPIRED?"expired":"active"))).hasSize(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,operator)).isZero();
    }
    @Test void summaryUsesOneClockForStartExpiryAndManualGrantBoundaries() {
        Instant anchor=Instant.parse("2040-01-01T00:00:00Z");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO platform_subjects VALUES (?)",id);
        var fixtures=new ScenarioPurchases(jdbc,json,new TestAccountPolicy(TestAccountPolicyTest.local()));
        var scenario=LearnerScenario.all().stream().filter(s->s.membership()!=null
                && s.term()!=LearnerScenario.Term.EXPIRED && s.scheduled()==null
                && s.extraMembership()==null && s.upgradedFrom()==null).findFirst().orElseThrow();
        new TransactionTemplate(manager).executeWithoutResult(tx->fixtures.populate(id,id,scenario,anchor));
        var row=jdbc.queryForMap("SELECT starts_at,ends_at FROM membership_purchases WHERE account_id=?",id);
        Instant start=((java.sql.Timestamp)row.get("starts_at")).toInstant();
        Instant end=((java.sql.Timestamp)row.get("ends_at")).toInstant();
        jdbc.update("INSERT INTO account_grants VALUES (?, 'learn:clock-fixture', ?)",id,java.sql.Timestamp.from(end));
        var actor=new AccountPrincipal(id,"clock-fixture","Synthetic",true,false);
        var staff=mock(StaffAccessService.class);
        java.util.function.Function<Instant,AccessSummaryService.Summary> at=instant->
                new AccessSummaryService(jdbc,json,staff,Clock.fixed(instant,ZoneOffset.UTC)).get(actor,id);
        assertThat(at.apply(start.minusSeconds(1)).label()).doesNotContain("Regular · ","Promo · ");
        assertThat(at.apply(start).label()).contains(scenario.membership().contains("promo")?"Promo · ":"Regular · ");
        assertThat(at.apply(end.minusSeconds(1)).label()).contains(scenario.membership().contains("promo")?"Promo · ":"Regular · ");
        assertThat(at.apply(end.minusSeconds(1)).grantedCourseIds()).contains("learn:clock-fixture");
        assertThat(at.apply(end).label()).isEqualTo("Free");
        assertThat(at.apply(end).grantedCourseIds()).isEmpty();
    }
    @Test void summaryChecksActorAndExcludesExpiredManualGrants() {
        UUID id=UUID.randomUUID(), other=UUID.randomUUID();
        var actor=new AccountPrincipal(id,"synthetic","Synthetic",true,false);
        var staff=mock(StaffAccessService.class);
        when(staff.permissions(actor)).thenReturn(StaffPermissions.forRoles(false,Set.of()));
        var summaries=new AccessSummaryService(jdbc,json,staff);
        assertThatThrownBy(()->summaries.get(null,id)).hasMessageContaining("Sign in");
        assertThatThrownBy(()->summaries.get(actor,other)).hasMessageContaining("Author");
        jdbc.update("INSERT INTO account_grants VALUES (?, 'learn:core-java', null), (?, 'grow:expired', now()-interval '1 day')",id,id);
        jdbc.update("INSERT INTO staff_access VALUES (?, '[\"grow:api-design\"]'::jsonb)",id);
        assertThat(summaries.get(actor,id).grantedCourseIds()).containsExactly("grow:api-design","learn:core-java");
        for(var roles:List.of(Set.of("admin"),Set.of("co-author"))) {
            when(staff.permissions(actor)).thenReturn(StaffPermissions.forRoles(false,roles));
            assertThat(summaries.get(actor,other).label()).isEqualTo("Free");
        }
    }
    @Test void failureRollsBackTheWholeScenario() {
        UUID account=UUID.randomUUID(); jdbc.update("INSERT INTO platform_subjects VALUES (?)",account);
        var fixtures=new ScenarioPurchases(jdbc,json,new TestAccountPolicy(TestAccountPolicyTest.local()));
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            fixtures.populate(account,account,LearnerScenario.of(17),Instant.now());
            throw new IllegalStateException("rollback fixture");
        })).hasMessage("rollback fixture");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,account)).isZero();
    }
}
