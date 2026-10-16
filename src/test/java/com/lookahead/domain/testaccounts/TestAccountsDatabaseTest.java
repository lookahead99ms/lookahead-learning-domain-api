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
  for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql","V11__free_plan_allowances.sql","V12__created_test_accounts.sql","V13__real_account_maintenance.sql"))
   jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",admin.accountId());
 }
 @BeforeEach void service(){
  // This class shares one disposable schema; dynamically created learners are test-local.
  jdbc.update("DELETE FROM test_account_registry WHERE initialization='created'");
  jdbc.update("DELETE FROM test_account_creations");
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
 @Test void createdLearnersSpendLifetimeAllowanceAndDeletionErasesPlans() {
  var operator=mock(TestIdentityOperator.class);
  var repository=mock(StaffAccessRepository.class);
  when(repository.forAccount(admin.accountId())).thenReturn(new StaffAccessRepository.Assignment(
      UUID.randomUUID(),admin.accountId(),null,Set.of("admin"),Set.of(),false,1));
  var owner=mock(LocalAuthorAccess.class);
  var created=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),
      new TestAccountPolicy(TestAccountPolicyTest.local()),repository,owner,operator);
  var key=UUID.randomUUID();var request=new CreatedTestAccounts.Create(key,"synthetic-learner-fixture-only");
  var first=created.create(admin,request);
  assertThat(first.active()).isTrue();
  assertThat(created.create(admin,request).accountId()).isEqualTo(first.accountId());
  assertThat(created.allowance(admin)).isEqualTo(new CreatedTestAccounts.Allowance(10,1,9));
  verify(operator,times(1)).provision(eq(first.accountId()),eq(first.username()),anyString());
  verify(operator,times(2)).activate(first.accountId(),first.username());
  UUID planId=UUID.randomUUID(),versionId=UUID.randomUUID();
  tx.executeWithoutResult(status->{
   jdbc.update("INSERT INTO plans(id,account_id,revision,current_version,goal,progress,name,plan_number) VALUES (?,?,1,?,'Test','{}'::jsonb,'Test',1)",
       planId,first.accountId(),versionId);
   jdbc.update("INSERT INTO plan_versions(id,account_id,plan_id,parent_version,snapshot,snapshot_digest,membership,provenance,recovery,reason) VALUES (?,?,?,NULL,'{}'::jsonb,'synthetic','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'test')",
       versionId,first.accountId(),planId);
   jdbc.update("INSERT INTO plan_activity(id,account_id,plan_id,version_id,revision,kind,payload) VALUES (?,?,?,?,1,'test','{}'::jsonb)",
       UUID.randomUUID(),first.accountId(),planId,versionId);
  });
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:core-java')",first.accountId());
  jdbc.update("INSERT INTO free_plan_creations(account_id,plan_id) VALUES (?,?)",first.accountId(),planId);
  jdbc.update("INSERT INTO free_plan_deletions(account_id,plan_id) VALUES (?,?)",first.accountId(),planId);
  UUID purchaseId=UUID.randomUUID(),auditId=UUID.randomUUID();
  String paymentKey="fixture-"+purchaseId;
  jdbc.update("""
      INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,
        price_paid_cents,status,starts_at,ends_at,source,actor_id,provider)
      VALUES (?,?,'learn','{}'::jsonb,?,?,0,'succeeded',now(),now()+interval '1 day','checkout',?,'mock')
      """,purchaseId,first.accountId(),"0".repeat(64),paymentKey,first.accountId());
  jdbc.update("INSERT INTO membership_mock_payments VALUES (?,? ,0,'USD','succeeded')",paymentKey,"pi_fixture_"+purchaseId);
  jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,'purchase-created',?,'{}'::jsonb)",
      auditId,admin.accountId(),purchaseId.toString());
  assertThatThrownBy(()->jdbc.update("DELETE FROM membership_audit WHERE id=?",auditId)).hasMessageContaining("Membership audit is append-only");
  created.delete(admin,first.accountId());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM plans WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_versions WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_activity WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM free_plan_creations WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM free_plan_deletions WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,first.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_mock_payments WHERE idempotency_key=?",Integer.class,paymentKey)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_audit WHERE id=?",Integer.class,auditId)).isZero();
  assertThat(jdbc.queryForObject("SELECT state FROM test_account_creations WHERE account_id=?",String.class,first.accountId())).isEqualTo("deleted");
  assertThat(created.allowance(admin)).isEqualTo(new CreatedTestAccounts.Allowance(10,1,9));
  verify(operator).disable(first.accountId(),first.username());
  assertThatThrownBy(()->created.create(admin,request)).hasMessageContaining("deleted");
 }
 @Test void adminAndCoauthorLifetimeLimitsSurviveDeletionAndProtectStaff() {
  var operator=mock(TestIdentityOperator.class);var repository=mock(StaffAccessRepository.class);
  var owner=mock(LocalAuthorAccess.class);
  UUID coauthorId=UUID.randomUUID();
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",coauthorId);
  var coauthor=new AccountPrincipal(coauthorId,"coauthor-operator","Co-author",true,false);
  when(repository.forAccount(admin.accountId())).thenReturn(new StaffAccessRepository.Assignment(
      UUID.randomUUID(),admin.accountId(),null,Set.of("admin"),Set.of(),false,1));
  when(repository.forAccount(coauthorId)).thenReturn(new StaffAccessRepository.Assignment(
      UUID.randomUUID(),coauthorId,null,Set.of("co-author"),Set.of(),false,1));
  var created=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),
      new TestAccountPolicy(TestAccountPolicyTest.local()),repository,owner,operator);
  CreatedTestAccounts.Created first=null;
  for(int n=0;n<10;n++){
   var item=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),"synthetic-learner-fixture-only"));
   if(n==0)first=item;
  }
  assertThat(created.allowance(admin).remaining()).isZero();
  var extra=new CreatedTestAccounts.Create(UUID.randomUUID(),"synthetic-learner-fixture-only");
  assertThatThrownBy(()->created.create(admin,extra)).hasMessageContaining("limit");
  created.delete(admin,first.accountId());
  assertThatThrownBy(()->created.create(admin,extra)).hasMessageContaining("limit");
  UUID anotherAdminLearner=jdbc.queryForObject("SELECT account_id FROM test_account_creations WHERE creator_id=? AND state='active' LIMIT 1",UUID.class,admin.accountId());
  created.delete(coauthor,anotherAdminLearner);
  assertThat(created.allowance(admin).used()).isEqualTo(10);
  UUID staleKey=UUID.randomUUID(),staleId=UUID.randomUUID();
  jdbc.update("INSERT INTO test_account_creations(creator_id,mutation_key,account_id,username,state,created_at) VALUES (?,?,?,'test-aaaaaaaaaaaaaaaaaaaaaaaa','pending',clock_timestamp()-interval '1 hour')",
      admin.accountId(),staleKey,staleId);
  assertThatThrownBy(()->created.create(admin,new CreatedTestAccounts.Create(staleKey,"synthetic-learner-fixture-only")))
      .hasMessageContaining("limit");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry WHERE account_id=?",Integer.class,staleId)).isZero();
  for(int n=0;n<20;n++)created.create(coauthor,new CreatedTestAccounts.Create(UUID.randomUUID(),"synthetic-learner-fixture-only"));
  assertThat(created.allowance(coauthor).remaining()).isZero();
  assertThatThrownBy(()->created.create(coauthor,new CreatedTestAccounts.Create(UUID.randomUUID(),"synthetic-learner-fixture-only"))).hasMessageContaining("limit");
  seed();
  var staffId=TestAccountRoster.entries().stream().filter(TestAccountRoster.Entry::staff).findFirst().orElseThrow().accountId();
  when(repository.forAccount(staffId)).thenReturn(new StaffAccessRepository.Assignment(
      UUID.randomUUID(),staffId,null,Set.of("co-author"),Set.of(),false,1));
  assertThatThrownBy(()->created.delete(coauthor,staffId)).hasMessageContaining("role cannot delete");
 }
 @Test void ownerAndCoauthorCanDeleteLowerRankedStaffAndRevokeAssignments() {
  seed();
  var roster=TestAccountRoster.entries();
  var coauthorTarget=roster.stream().filter(e->e.username().equals("coauthor01")).findFirst().orElseThrow();
  var coauthorActor=roster.stream().filter(e->e.username().equals("coauthor02")).findFirst().orElseThrow();
  var adminTarget=roster.stream().filter(e->e.username().equals("admin01")).findFirst().orElseThrow();
  var owner=mock(LocalAuthorAccess.class);when(owner.allowed(admin)).thenReturn(true);
  var identity=mock(TestIdentityOperator.class);
  var managed=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),
      new TestAccountPolicy(TestAccountPolicyTest.local()),new StaffAccessRepository(jdbc,json),owner,identity);
  tx.executeWithoutResult(status->{
   managed.delete(admin,coauthorTarget.accountId());
   assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,coauthorTarget.accountId())).isEqualTo("[]");
   assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,coauthorTarget.accountId())).isEqualTo("deleted");
   var coauthor=new AccountPrincipal(coauthorActor.accountId(),coauthorActor.username(),"Co-author",true,false);
   managed.delete(coauthor,adminTarget.accountId());
   assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,adminTarget.accountId())).isEqualTo("[]");
   verify(identity).disable(coauthorTarget.accountId(),coauthorTarget.username());
   verify(identity).disable(adminTarget.accountId(),adminTarget.username());
   status.setRollbackOnly();
  });
 }
 @Test void realAccountMustBeDowngradedBeforeLocalDeletionAndPurchaseAuditSurvives() {
  UUID real=UUID.randomUUID(),purchase=UUID.randomUUID();
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",real);
  jdbc.update("INSERT INTO staff_access(id,account_id,roles,topic_grants,full_curriculum) VALUES (?,?,'[\"admin\"]'::jsonb,'[]'::jsonb,true)",UUID.randomUUID(),real);
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:core-java')",real);
  jdbc.update("""
      INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,
        price_paid_cents,status,starts_at,ends_at,source,actor_id,provider)
      VALUES (?,?,'learn','{}'::jsonb,?,?,0,'succeeded',now(),now()+interval '1 day','checkout',?,'mock')
      """,purchase,real,"0".repeat(64),"fixture-"+purchase,real);
  var owner=mock(StaffAccessService.class);var directory=mock(AccountDirectory.class);
  when(directory.byId(real)).thenReturn(new AccountDirectory.Account(real,"real-user","Real User",null));
  var identity=mock(TestIdentityOperator.class);
  var maintenance=new RealAccountMaintenance(jdbc,owner,directory,new TestAccountPolicy(TestAccountPolicyTest.local()),identity,
      new DataSourceTransactionManager(jdbc.getDataSource()));
  assertThatThrownBy(()->maintenance.deleteLearner(admin,real,new RealAccountMaintenance.Confirmation("DELETE"))).hasMessageContaining("Downgrade");
  verifyNoInteractions(identity);
  maintenance.downgrade(admin,real,new RealAccountMaintenance.Confirmation("DOWNGRADE"));
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,real)).isEqualTo("[]");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE account_id=?",Integer.class,real)).isZero();
  assertThat(jdbc.queryForObject("SELECT superseded_at IS NOT NULL FROM membership_purchases WHERE id=?",Boolean.class,purchase)).isTrue();
  maintenance.deleteLearner(admin,real,new RealAccountMaintenance.Confirmation("DELETE"));
  verify(identity).disableReal(real);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE id=?",Integer.class,purchase)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM real_account_maintenance WHERE account_id=? AND action='delete'",Integer.class,real)).isEqualTo(1);
  maintenance.deleteLearner(admin,real,new RealAccountMaintenance.Confirmation("DELETE"));
  verify(identity,times(1)).disableReal(real);
 }
 @Test void ownerCanDeleteRosterLearnerWithPurchaseAuditWithoutLeavingLoginDisabledOnly() {
  seed();
  UUID id=learner(27),purchaseId=UUID.randomUUID(),auditId=UUID.randomUUID();
  String paymentKey="fixture-"+purchaseId;
  jdbc.update("""
      INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,
        price_paid_cents,status,starts_at,ends_at,source,actor_id,provider)
      VALUES (?,?,'learn','{}'::jsonb,?,?,0,'succeeded',now(),now()+interval '1 day','checkout',?,'mock')
      """,purchaseId,id,"0".repeat(64),paymentKey,id);
  jdbc.update("INSERT INTO membership_mock_payments VALUES (?,? ,0,'USD','succeeded')",paymentKey,"pi_fixture_"+purchaseId);
  jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,'purchase-created',?,'{}'::jsonb)",
      auditId,admin.accountId(),purchaseId.toString());
  var identity=mock(TestIdentityOperator.class);
  var owner=mock(LocalAuthorAccess.class);when(owner.allowed(admin)).thenReturn(true);
  var managed=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),
      new TestAccountPolicy(TestAccountPolicyTest.local()),mock(StaffAccessRepository.class),owner,identity);
  managed.delete(admin,id);
  verify(identity).disable(id,"learner27");
  assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,id)).isEqualTo("deleted");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE id=?",Integer.class,purchaseId)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_mock_payments WHERE idempotency_key=?",Integer.class,paymentKey)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_audit WHERE id=?",Integer.class,auditId)).isZero();
 }
 @Test void activationFailureKeepsOneManagedRecordForSameKeyRecovery() {
  var operator=mock(TestIdentityOperator.class);var repository=mock(StaffAccessRepository.class);
  when(repository.forAccount(admin.accountId())).thenReturn(new StaffAccessRepository.Assignment(
      UUID.randomUUID(),admin.accountId(),null,Set.of("admin"),Set.of(),false,1));
  var created=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),
      new TestAccountPolicy(TestAccountPolicyTest.local()),repository,mock(LocalAuthorAccess.class),operator);
  UUID key=UUID.randomUUID();var request=new CreatedTestAccounts.Create(key,"synthetic-learner-fixture-only");
  doThrow(new IllegalStateException("synthetic lost activation reply")).doNothing().when(operator).activate(any(),any());
  assertThatThrownBy(()->created.create(admin,request)).hasMessageContaining("lost activation reply");
  assertThat(created.allowance(admin).used()).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry WHERE creator_id=?",Integer.class,admin.accountId())).isEqualTo(1);
  created.create(admin,request);
  assertThat(created.allowance(admin).used()).isEqualTo(1);
  verify(operator,times(1)).provision(any(),any(),any());
  verify(operator,times(2)).activate(any(),any());
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
