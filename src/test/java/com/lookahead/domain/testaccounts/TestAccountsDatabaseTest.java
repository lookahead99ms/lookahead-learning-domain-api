package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.*;
import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.learning.content.security.*;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="DLV921_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestAccountsDatabaseTest {
 JdbcTemplate jdbc; TransactionTemplate tx; String schema; ObjectMapper json=new ObjectMapper();
 StaffAccessService staff; ScenarioPlans plans; TestAccountsService service;
 AccountPrincipal admin=new AccountPrincipal(UUID.randomUUID(),"operator","Operator",true,false);
 @BeforeAll void setup() throws Exception {
  String url=System.getenv("DLV921_DATABASE_URL");assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:4392/lookahead_domain_review");
  String password=Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
  schema="test_accounts_"+UUID.randomUUID().toString().replace("-","");
  new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
  var source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);
  jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
  jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
  for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V6__staff_access.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql"))
   jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",admin.accountId());
 }
 @BeforeEach void service(){
  staff=mock(StaffAccessService.class);plans=mock(ScenarioPlans.class);
  when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(false,Set.of("admin")));
  var catalog=mock(SnapshotValidator.class);when(catalog.allTopicIds()).thenReturn(Set.of("learn:hands-on-dsa","grow:api-design"));
  var policy=new TestAccountPolicy(TestAccountPolicyTest.local());
  service=new TestAccountsService(jdbc,json,policy,new ScenarioPurchases(jdbc,json,policy),plans,staff,new AccessSummaryService(jdbc,json,staff),catalog);
 }
 @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 UUID learner(int n){return TestAccountRoster.entries().get(n-1).accountId();}
 long revision(UUID id){return jdbc.queryForObject("SELECT revision FROM test_account_registry WHERE account_id=?",Long.class,id);}
 void seed(){tx.executeWithoutResult(t->service.seed());}
 @Test void seedPreservesExistingLearnersAndOwnerAndIsRepeatable(){
  // This test uses whichever rows earlier tests created, preserving their current bytes.
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",LocalAuthorAccess.ACCOUNT_ID);
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?, 'owner-private') ON CONFLICT DO NOTHING",LocalAuthorAccess.ACCOUNT_ID);
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",learner(1));
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?, 'learner-private') ON CONFLICT DO NOTHING",learner(1));
  seed();
  String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY username)::text FROM test_account_registry t",String.class);
  int created=tx.execute(t->service.seed());assertThat(created).isZero();
  assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY username)::text FROM test_account_registry t",String.class)).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry",Integer.class)).isEqualTo(35);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE topic_id IN ('owner-private','learner-private')",Integer.class)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry WHERE account_id=?",Integer.class,LocalAuthorAccess.ACCOUNT_ID)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM reportable_platform_subjects WHERE id=?",Integer.class,learner(1))).isZero();
  assertThat(service.list(admin)).hasSize(35);
 }
 @Test void resetIsConfirmedRevisionCheckedRetrySafeAndRetainsPurchaseHistory(){
  seed();UUID id=learner(17);long before=revision(id);
  var reset=new TestAccountsService.Reset(UUID.randomUUID(),before,"learner17","scenario");
  tx.executeWithoutResult(t->service.reset(admin,id,reset));
  long after=revision(id);assertThat(after).isGreaterThan(before);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=? AND superseded_at IS NOT NULL",Integer.class,id)).isGreaterThanOrEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT last_changed_by FROM test_account_registry WHERE account_id=?",String.class,id)).isEqualTo(admin.accountId().toString());
  tx.executeWithoutResult(t->service.reset(admin,id,reset));assertThat(revision(id)).isEqualTo(after);
  assertThatThrownBy(()->tx.executeWithoutResult(t->service.reset(admin,id,new TestAccountsService.Reset(UUID.randomUUID(),before,"learner17","scenario")))).hasMessageContaining("changed");
  assertThatThrownBy(()->service.reset(admin,id,new TestAccountsService.Reset(UUID.randomUUID(),after,"learner17",null))).hasMessageContaining("Confirm");
  assertThatThrownBy(()->service.reset(admin,LocalAuthorAccess.ACCOUNT_ID,reset)).hasMessageContaining("Managed");
  assertThatThrownBy(()->service.reset(admin,id,new TestAccountsService.Reset(UUID.randomUUID(),after,"learner17","legacy"))).hasMessageContaining("Legacy");
 }
 @Test void failedPopulationRollsBackAndCoauthorCannotReset(){
  seed();UUID id=learner(6);long before=revision(id);
  doThrow(new IllegalStateException("fixture failure")).when(plans).populate(eq(id),any(),any());
  assertThatThrownBy(()->tx.executeWithoutResult(t->service.reset(admin,id,new TestAccountsService.Reset(UUID.randomUUID(),before,"learner06","scenario")))).hasMessageContaining("fixture failure");
  assertThat(revision(id)).isEqualTo(before);
  when(staff.permissions(admin)).thenReturn(StaffPermissions.forRoles(false,Set.of("co-author")));
  assertThat(service.list(admin)).hasSize(35);
  assertThatThrownBy(()->service.reset(admin,id,new TestAccountsService.Reset(UUID.randomUUID(),before,"learner06","scenario"))).hasMessageContaining("Only owner or admin");
  when(staff.permissions(admin)).thenReturn(StaffPermissions.forRoles(false,Set.of()));
  assertThatThrownBy(()->service.list(admin)).hasMessageContaining("Owner, admin or co-author");
 }
 @Test void legacyPresetRestoresExistingOptionsAndDeletingGrantsTouchesRegistry(){
  seed();clearInvocations(plans);UUID id=learner(9);
  tx.executeWithoutResult(t->service.reset(admin,id,new TestAccountsService.Reset(UUID.randomUUID(),revision(id),"learner09","legacy")));
  assertThat(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id=?",String.class,id)).containsExactlyInAnyOrder("learn:hands-on-dsa","learn:algorithmic-patterns");
  long before=revision(id);jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
  assertThat(revision(id)).isGreaterThan(before);
  verify(plans,never()).populate(eq(id),any(),any());
 }
 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="TEST_ACCOUNT_FIXTURES",matches=".+")
 void realPublishedPlansPersistForAll27Scenarios() throws Exception {
  var catalog=new SnapshotValidator(json,json.readTree(Files.readString(Path.of(System.getenv("TEST_ACCOUNT_CATALOG")))));
  String root=System.getenv("TEST_ACCOUNT_PUBLICATION");
  var publication=new com.lookahead.learning.content.service.ProtectedContentPolicy(json,Path.of(root,"publication.json").toString(),Path.of(root,"content").toString());
  var hashes=new com.lookahead.learning.content.util.PlanJson(json);
  var policy=new TestAccountPolicy(TestAccountPolicyTest.local());
  var lifecycle=new com.lookahead.learning.content.service.PlanLifecycle(jdbc,json);
  var realPlans=new ScenarioPlans(publication,catalog,new com.lookahead.learning.content.repository.PlanRepository(jdbc,hashes),lifecycle,hashes,json,policy,System.getenv("TEST_ACCOUNT_FIXTURES"));
  var purchases=new ScenarioPurchases(jdbc,json,policy);
  for(var row:LearnerScenario.all()) {
   UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);
   tx.executeWithoutResult(t->{purchases.populate(id,admin.accountId(),row,java.time.Instant.now());realPlans.populate(id,row,java.time.Instant.now());});
   int expected=row.planKind()==LearnerScenario.PlanKind.NONE?0:row.planKind()==LearnerScenario.PlanKind.SEVERAL?2:1;
   assertThat(jdbc.queryForObject("SELECT count(*) FROM plans WHERE account_id=?",Integer.class,id)).as(row.username()).isEqualTo(expected);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_versions WHERE account_id=?",Integer.class,id)).isEqualTo(expected);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_lifecycle WHERE account_id=?",Integer.class,id)).isEqualTo(expected);
   for(String raw:jdbc.queryForList("SELECT progress::text FROM plans WHERE account_id=?",String.class,id)) {
    var progress=json.readTree(raw);
    if(row.progress()==LearnerScenario.Progress.NOT_STARTED)assertThat(progress.path("completedSessionIds").size()).isZero();
    else assertThat(progress.path("completedSessionIds").size()).isPositive();
   }
  }
 }

}
