package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.*;
import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.*;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DLV-969 S35 against the disposable cloud-auth database: the full Domain schema (V1-V15, V17-V19) in a fresh schema,
 * the policy in dev mode. DEV test accounts are database-only: no identity provider, no password; staff use them
 * through test sessions inside the DEV gate.
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudTestAccountsDatabaseTest {
 static final String ISSUER="https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
 JdbcTemplate jdbc; DriverManagerDataSource source; String schema; ObjectMapper json=new ObjectMapper();
 AccountPrincipal admin=new AccountPrincipal(UUID.randomUUID(),"operator","Operator",true,false);
 AccountPrincipal owner=new AccountPrincipal(UUID.randomUUID(),"owner@example.test","Owner",true,true);
 TestAccountPolicy policy=new TestAccountPolicy(TestAccountPolicyTest.cloud("dev"));
 StaffAccessRepository repository; LocalAuthorAccess ownerAccess; ScenarioPlans plans; CreatedTestAccounts created;
 @BeforeAll void setup() throws Exception {
  String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
  if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
  String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
  schema="cloud_test_accounts_"+UUID.randomUUID().toString().replace("-","");
  new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
  source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);
  jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
  jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
  for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql","V11__free_plan_allowances.sql","V12__created_test_accounts.sql","V13__real_account_maintenance.sql","V14__managed_test_purchase_purge_grants.sql","V15__retain_runtime_audit.sql","V17__dev_access_admission.sql","V18__access_removal_and_staff_aliases.sql","V19__cloud_identities.sql","V20__cloud_merge_tokens.sql"))
   jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
  for(var actor:List.of(admin,owner))jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",actor.accountId());
 }
 @BeforeEach void service(){
  // Every test starts without test accounts or their purchases, plans and grants; as in DEV, audit rows stay.
  var erasure=new AccountDataErasure(jdbc);var transactions=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
  for(UUID id:jdbc.queryForList("SELECT account_id FROM test_account_registry",UUID.class))transactions.executeWithoutResult(status->erasure.erase(id,false));
  jdbc.update("DELETE FROM cloud_sign_ins");jdbc.update("DELETE FROM cloud_account_states");
  // The reset ledger is append-only by row trigger; TRUNCATE (no row triggers) clears it between tests.
  jdbc.execute("TRUNCATE test_account_resets");jdbc.update("DELETE FROM test_account_registry");jdbc.update("DELETE FROM test_account_creations");
  jdbc.update("DELETE FROM staff_access");jdbc.update("UPDATE cloud_accounts SET admitted=false");jdbc.update("DELETE FROM cloud_accounts");
  repository=mock(StaffAccessRepository.class);
  when(repository.forAccount(admin.accountId())).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(),admin.accountId(),null,Set.of("admin"),Set.of(),false,1));
  ownerAccess=mock(LocalAuthorAccess.class);when(ownerAccess.allowed(owner)).thenReturn(true);
  plans=mock(ScenarioPlans.class);
  created=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(source),policy,repository,ownerAccess,mock(TestIdentityOperator.class));
  created.configureAccounts(rosterService(Set.of("admin")));
 }
 @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 TestAccountsService rosterService(Set<String> roles){
  var staff=mock(StaffAccessService.class);when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(false,roles));
  var catalog=mock(SnapshotValidator.class);when(catalog.allTopicIds()).thenReturn(Set.of("learn:hands-on-dsa"));
  return new TestAccountsService(jdbc,json,policy,new ScenarioPurchases(jdbc,json,policy),plans,staff,new AccessSummaryService(jdbc,json,staff),catalog);
 }
 DevTestRoster roster(){return new DevTestRoster(jdbc,new DataSourceTransactionManager(source),policy,ownerAccess,rosterService(Set.of("admin")));}
 DevTestSessions sessions(){return new DevTestSessions(jdbc,policy,ISSUER,"synthetic-gateway-secret");}
 /** A real DEV account that could open the gate: its cloud_accounts row on the Cognito issuer, and its staff roles. */
 UUID holder(String subject,boolean author,String... roles){
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);
  jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,username,display_name) VALUES (?,?,?,true,true,?,?,?)",id,ISSUER,subject,author,subject,subject);
  if(roles.length>0)jdbc.update("INSERT INTO staff_access(id,account_id,roles,topic_grants,full_curriculum,revision) VALUES (?,?,?::jsonb,'[]',true,1)",UUID.randomUUID(),id,json.writeValueAsString(List.of(roles)));
  return id;
 }

 @Test void createInDevWritesTheAdmittedTestRowAndTheChosenScenarioWithoutAPasswordAndIsRetrySafe(){
  var key=UUID.randomUUID();var request=new CreatedTestAccounts.Create(key,null,"learner05",false);
  var first=created.create(admin,request);
  assertThat(first.active()).isTrue();
  // An address on the owner's domain that never signs in; it fits the varchar(32) username columns.
  assertThat(first.username()).matches("test-[0-9a-f]{10}@lookahead\\.dev").hasSizeLessThanOrEqualTo(32);
  var row=jdbc.queryForMap("SELECT issuer,subject,admitted,enabled,author_access,username,display_name,admitted_by,verified_email FROM cloud_accounts WHERE account_id=?",first.accountId());
  assertThat(row).containsEntry("issuer",TestAccountRoster.TEST_ISSUER).containsEntry("subject",first.accountId().toString())
      .containsEntry("admitted",true).containsEntry("enabled",true).containsEntry("author_access",false)
      .containsEntry("username",first.username()).containsEntry("admitted_by",admin.accountId());
  assertThat(row.get("verified_email")).isNull();
  // DLV-969 S36: its one identity is on the test issuer, provider 'test', without an email, so it never links.
  assertThat(jdbc.queryForMap("SELECT issuer,subject,username,provider,verified_email,link_source FROM cloud_identities WHERE account_id=?",first.accountId()))
      .containsEntry("issuer",TestAccountRoster.TEST_ISSUER).containsEntry("subject",first.accountId().toString()).containsEntry("username",first.username())
      .containsEntry("provider","test").containsEntry("link_source","first_contact").containsEntry("verified_email",null);
  assertThat(jdbc.queryForMap("SELECT action,actor_id,issuer FROM cloud_admission_events WHERE account_id=?",first.accountId()))
      .containsEntry("action","admit").containsEntry("actor_id",admin.accountId()).containsEntry("issuer",TestAccountRoster.TEST_ISSUER);
  // learner05: Learn, active, ready-made plan in progress.
  assertThat(jdbc.queryForList("SELECT plan_id FROM membership_purchases WHERE account_id=?",String.class,first.accountId())).containsExactly("learn");
  verify(plans).populate(eq(first.accountId()),argThat(scenario->scenario.number()==5),any());
  assertThat(jdbc.queryForMap("SELECT initialization,creator_id,creation_state FROM test_account_registry WHERE account_id=?",first.accountId()))
      .containsEntry("initialization","created").containsEntry("creator_id",admin.accountId()).containsEntry("creation_state","active");
  // Same key again: nothing new, and the allowance is spent once.
  assertThat(created.create(admin,request).accountId()).isEqualTo(first.accountId());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=?",Integer.class,first.accountId())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,first.accountId())).isEqualTo(1);
  assertThat(created.allowance(admin)).extracting("limit","used","remaining").containsExactly(10,1,9);
  // Without a scenario: a learner without access, as before.
  var blank=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=?",Integer.class,blank.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE NOT admitted",Integer.class)).isZero();
  assertThatThrownBy(()->created.create(admin,null)).hasMessageContaining("request key");
 }
 /** DLV-969 (owner, 2026-11-04): "Create test account" makes learner test accounts only, for every role, the owner included. */
 @Test void everyRoleCreatesLearnerTestAccountsOnlyAndTheAllowanceListsLearnerScenariosOnly(){
  var coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",coauthor.accountId());
  when(repository.forAccount(coauthor.accountId())).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(),coauthor.accountId(),null,Set.of("co-author"),Set.of(),false,1));
  assertThatThrownBy(()->created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner99",false)))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("UNKNOWN_SCENARIO"));
  try(var log=com.lookahead.domain.LogCapture.start()){
   for(var creator:List.of(owner,coauthor,admin))for(String staffScenario:List.of("co-author","admin"))
    assertThatThrownBy(()->created.create(creator,new CreatedTestAccounts.Create(UUID.randomUUID(),null,staffScenario,false))).as(creator.getUsername()+" "+staffScenario)
        .isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("TEST_ACCOUNT_LEARNER_ONLY");
          assertThat(f.getMessage()).isEqualTo("Only learner test accounts can be created. Staff test accounts come from the roster.");});
   var refused=log.lines("test-account.create");
   assertThat(refused).hasSize(6).allSatisfy(line->{assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
     assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("reason","TEST_ACCOUNT_LEARNER_ONLY");});
  }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry",Integer.class)).isZero();
  for(var creator:List.of(owner,coauthor,admin)){
   var learner=created.create(creator,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner03",false));
   assertThat(jdbc.queryForList("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,learner.accountId())).isEmpty();
   assertThat(created.allowance(creator).scenarios()).as(creator.getUsername()).hasSize(27).allSatisfy(t->assertThat(t.roles()).isEmpty())
       .extracting(TestAccountTemplates.Template::id).contains("learner01","learner27").doesNotContain("co-author","admin");
  }
  assertThat(created.allowance(owner).limit()).isEqualTo(Integer.MAX_VALUE);
  // Nothing was reserved by a refused request; limits are unchanged.
  assertThat(created.allowance(admin)).extracting("limit","used").containsExactly(10,1);
  assertThat(created.allowance(coauthor)).extracting("limit","used").containsExactly(20,1);
 }
 @Test void deleteKeepsTheAdmittedRowAndRemovesTheAccountFromEveryTestSession(){
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner02",false));
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:core-java')",learner.accountId());
  String ownerSub="sub-owner";holder(ownerSub,true);
  assertThat(sessions().actAs(ownerSub,learner.accountId()).username()).isEqualTo(learner.username());
  created.delete(admin,learner.accountId());
  assertThat(jdbc.queryForMap("SELECT admitted,enabled FROM cloud_accounts WHERE account_id=?",learner.accountId())).containsEntry("admitted",true).containsEntry("enabled",true);
  assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,learner.accountId())).isEqualTo("deleted");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE account_id=?",Integer.class,learner.accountId())).isZero();
  assertThatThrownBy(()->sessions().actAs(ownerSub,learner.accountId())).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(404));
  assertThat(sessions().usable(ownerSub)).isEmpty();
  assertThat(created.allowance(admin)).extracting("limit","used","remaining").containsExactly(10,1,9);
 }
 /** DLV-969 (owner decision 2026-11-03): a co-author sees who created or changed a test account as a role label. */
 @Test void onlyTheOwnerSeesWhoCreatedATestAccount(){
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null));
  var coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
  var aliases=mock(StaffAliases.class);when(aliases.label(admin.accountId())).thenReturn("Admin 1");
  var service=rosterService(Set.of("co-author"));service.configureAliases(aliases);
  var seen=service.list(coauthor).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow();
  assertThat(seen.get("creatorId").asString()).isEqualTo("Admin 1");
  assertThat(seen.get("lastChangedBy").asString()).isEqualTo("Admin 1");
  var own=service.list(admin).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow();
  assertThat(own.get("creatorId").asString()).isEqualTo(admin.accountId().toString());
  jdbc.update("UPDATE test_account_registry SET last_changed_by='not-an-account' WHERE account_id=?",learner.accountId());
  assertThat(service.list(coauthor).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow()
      .get("lastChangedBy").asString()).isEqualTo("Unknown");
  when(aliases.seesIdentities(coauthor)).thenReturn(true);
  assertThat(service.list(coauthor).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow()
      .get("creatorId").asString()).isEqualTo(admin.accountId().toString());
  verify(aliases,never()).label(learner.accountId());
 }
 @Test void devListsItsOwnTestAccountsAndRefusesTheLocalSeed(){
  var service=rosterService(Set.of("admin"));
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null));
  // A Local-seed-shaped row (no creator) stays invisible and cannot be reset in DEV.
  UUID seeded=TestAccountRoster.entries().getFirst().accountId();
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",seeded);
  jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,?,'scenario','seed')",seeded,"learner01");
  var listed=service.list(admin);
  assertThat(listed).hasSize(1);
  assertThat(listed.getFirst().get("accountId").asString()).isEqualTo(learner.accountId().toString());
  assertThat(listed.getFirst().get("legacyPresetAvailable").asBoolean()).isFalse();
  assertThatThrownBy(service::seed).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("TEST_ACCOUNTS_DISABLED");});
  assertThatThrownBy(()->service.reset(admin,seeded,new TestAccountsService.Reset(UUID.randomUUID(),1,"learner01@lookahead.dev","scenario")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("RESET_UNAVAILABLE"));
  assertThatThrownBy(()->rosterService(Set.of()).list(admin)).hasMessageContaining("Owner, admin or co-author");
  var maintenance=new RealAccountMaintenance(jdbc,mock(StaffAccessService.class),mock(AccountDirectory.class),policy,mock(TestIdentityOperator.class),new DataSourceTransactionManager(source));
  assertThatThrownBy(()->maintenance.deleteLearner(admin,UUID.randomUUID(),new RealAccountMaintenance.Confirmation("DELETE")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_ACCOUNTS_DISABLED"));
 }
 @Test void theOwnerCreatesTheRosterInBatchesWithItsScenariosAndRolesAndItResetsInDev(){
  var roster=roster();
  assertThat(roster.statusFor(owner)).extracting("total","ready","deleted").containsExactly(35,0,0);
  assertThat(roster.statusFor(admin)).isNull();
  assertThatThrownBy(()->roster.next(admin)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_ROSTER_OWNER_ONLY"));
  assertThat(roster.next(owner)).extracting("total","ready","deleted").containsExactly(35,5,0);
  int calls=1;DevTestRoster.Status status;
  do{status=roster.next(owner);calls++;}while(status.remaining()>0);
  assertThat(calls).isEqualTo(7);assertThat(status).extracting("total","ready","deleted").containsExactly(35,35,0);
  assertThat(roster.next(owner)).isEqualTo(status);
  // Plans are faked here, so every learner whose scenario has a plan counts as still without one (all but 01 and 10).
  assertThat(status.withoutPlan()).isEqualTo(25);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE issuer=? AND admitted AND admitted_by=?",Integer.class,TestAccountRoster.TEST_ISSUER,owner.accountId())).isEqualTo(35);
  var learner01=TestAccountRoster.entries().getFirst();
  assertThat(jdbc.queryForMap("SELECT username,display_name FROM cloud_accounts WHERE account_id=?",learner01.accountId()))
      .containsEntry("username","learner01@lookahead.dev").containsEntry("display_name","Learner01");
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,new TestAccountRoster.Entry("coauthor01",true).accountId())).isEqualTo("[\"co-author\"]");
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,new TestAccountRoster.Entry("admin06",true).accountId())).isEqualTo("[\"admin\"]");
  // learner12: Advance ending soon with Grow scheduled next.
  var learner12=new TestAccountRoster.Entry("learner12",false).accountId();
  assertThat(jdbc.queryForList("SELECT plan_id FROM membership_purchases WHERE account_id=? ORDER BY starts_at",String.class,learner12)).containsExactly("advance","grow");
  // DLV-969 (owner, 2026-11-04): an admin sees admins and learners, never the two co-authors.
  assertThat(rosterService(Set.of("admin")).list(admin)).hasSize(33);
  // Reset in DEV: only to the scenario, confirmed with the username the list shows.
  var service=rosterService(Set.of("admin"));
  long revision=jdbc.queryForObject("SELECT revision FROM test_account_registry WHERE account_id=?",Long.class,learner12);
  assertThatThrownBy(()->service.reset(admin,learner12,new TestAccountsService.Reset(UUID.randomUUID(),revision,"learner12","scenario"))).hasMessageContaining("Confirm");
  assertThatThrownBy(()->service.reset(admin,learner12,new TestAccountsService.Reset(UUID.randomUUID(),revision,"learner12@lookahead.dev","legacy")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("RESET_UNAVAILABLE"));
  service.reset(admin,learner12,new TestAccountsService.Reset(UUID.randomUUID(),revision,"learner12@lookahead.dev","scenario"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL",Integer.class,learner12)).isEqualTo(2);
  // DLV-969 (owner, 2026-11-04): a roster account is never deleted in DEV, not even by the owner; Reset is its way back.
  try(var log=com.lookahead.domain.LogCapture.start()){
   assertThatThrownBy(()->created.delete(owner,learner01.accountId()))
       .isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("ROSTER_ACCOUNT_PERMANENT");});
   // DLV-969: the refusal is one WARN line with its code and the ids, never the roster username.
   var refused=log.only("test-account.delete");
   assertThat(refused.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
   assertThat(refused.getFormattedMessage()).isEqualTo("Deleting test account "+learner01.accountId()+" refused: ROSTER_ACCOUNT_PERMANENT");
   assertThat(com.lookahead.domain.LogCapture.keys(refused)).containsEntry("reason","ROSTER_ACCOUNT_PERMANENT").containsEntry("target.id",learner01.accountId());
   log.containsNone(learner01.username());
  }
  assertThat(roster.next(owner)).extracting("total","ready","deleted").containsExactly(35,35,0);
  assertThat(service.list(admin)).allSatisfy(row->assertThat(row.get("canDelete").asBoolean()).isFalse());
 }
 /** DLV-969 (owner, 2026-11-04): in DEV a created test account is deleted by its creator or the owner, nobody else. */
 @Test void inDevOnlyTheCreatorOrTheOwnerDeletesACreatedTestAccount(){
  var other=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",other.accountId());
  when(repository.forAccount(other.accountId())).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(),other.accountId(),null,Set.of("co-author"),Set.of(),false,1));
  var first=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner02",false));
  var second=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner03",false));
  java.util.function.BiFunction<AccountPrincipal,TestAccountsService,Boolean> canDelete=(viewer,service)->service.list(viewer).stream()
      .filter(row->row.get("accountId").asString().equals(first.accountId().toString())).findFirst().orElseThrow().get("canDelete").asBoolean();
  // A co-author outranks an admin in Local, but in DEV did not create this account.
  assertThat(canDelete.apply(other,rosterService(Set.of("co-author")))).isFalse();
  assertThatThrownBy(()->created.delete(other,first.accountId()))
      .isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("DELETE_FORBIDDEN");});
  assertThat(canDelete.apply(admin,rosterService(Set.of("admin")))).isTrue();
  var ownerList=new TestAccountsService(jdbc,json,policy,new ScenarioPurchases(jdbc,json,policy),plans,ownerStaff(),
      new AccessSummaryService(jdbc,json,ownerStaff()),mock(SnapshotValidator.class));
  assertThat(canDelete.apply(owner,ownerList)).isTrue();
  created.delete(admin,first.accountId());
  created.delete(owner,second.accountId());
  assertThat(jdbc.queryForList("SELECT creation_state FROM test_account_registry WHERE account_id IN (?,?)",String.class,first.accountId(),second.accountId()))
      .containsExactly("deleted","deleted");
 }
 StaffAccessService ownerStaff(){
  var staff=mock(StaffAccessService.class);when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(true,Set.of()));
  return staff;
 }
 /** DLV-969 S37: DEV before its study plans are published; the roster carries on and says who still needs a plan. */
 @Test void aMissingPlanTemplateNeverStopsTheRosterInDevButStillFailsLocal(){
  doThrow(new ScenarioPlans.TemplateMissing("coding-interviews-d7-h1")).when(plans).populate(any(),any(),any());
  var roster=roster();
  var first=roster.next(owner);
  assertThat(first).extracting("total","ready","deleted","withoutPlan").containsExactly(35,5,0,4);
  // learner05 still got its scenario's membership; only the plan is missing.
  assertThat(jdbc.queryForList("SELECT plan_id FROM membership_purchases WHERE account_id=?",String.class,new TestAccountRoster.Entry("learner05",false).accountId())).containsExactly("learn");
  // Local keeps requiring the template.
  var localPolicy=new TestAccountPolicy(TestAccountPolicyTest.local());
  var staff=mock(StaffAccessService.class);when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(false,Set.of("admin")));
  var local=new TestAccountsService(jdbc,json,localPolicy,new ScenarioPurchases(jdbc,json,localPolicy),plans,staff,new AccessSummaryService(jdbc,json,staff),mock(SnapshotValidator.class));
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);
  assertThatThrownBy(()->local.applyCreated(id,"learner02",owner.accountId())).isInstanceOf(ScenarioPlans.TemplateMissing.class).hasMessageContaining("coding-interviews-d7-h1");
 }
 @Test void testSessionsFollowTheGateHoldersRoleAndStateOnEveryCall(){
  var roster=roster();while(roster.next(owner).remaining()>0){}
  String ownerSub="sub-owner",coauthorSub="sub-coauthor",adminSub="sub-admin",learnerSub="sub-learner",disabledSub="sub-disabled";
  holder(ownerSub,true);holder(coauthorSub,false,"co-author");holder(adminSub,false,"admin");holder(learnerSub,false);
  holder("sub-reviewer",false,"reviewer");holder("sub-tester",false,"tester");holder("sub-dual",false,"co-author","admin");
  // Signed in but never admitted: nothing.
  UUID waiting=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",waiting);
  jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,username,display_name) VALUES (?,?,'sub-waiting',false,true,false,'w','w')",waiting,ISSUER);
  UUID disabled=holder(disabledSub,false,"admin");
  jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",disabled,owner.accountId());
  var sessions=sessions();
  assertThat(sessions.usable(ownerSub)).hasSize(35);
  assertThat(sessions.usable(coauthorSub)).hasSize(35);
  assertThat(sessions.usable(adminSub)).hasSize(33).noneMatch(u->u.roles().contains("co-author"));
  // DLV-969 (owner, 2026-11-04): learners, reviewers and testers never switch;
  // a co-author who is also an admin is an admin here.
  for(String never:List.of(learnerSub,"sub-reviewer","sub-tester"))assertThat(sessions.usable(never)).as(never).isEmpty();
  assertThat(sessions.usable("sub-dual")).hasSize(33).noneMatch(u->u.roles().contains("co-author"));
  for(String nobody:new String[]{disabledSub,"sub-waiting","sub-unknown","",null})assertThat(sessions.usable(nobody)).as(String.valueOf(nobody)).isEmpty();
  var first=sessions.usable(adminSub).getFirst();
  assertThat(first.username()).isEqualTo("admin01@lookahead.dev");assertThat(first.roles()).containsExactly("admin");
  assertThat(sessions.usable(ownerSub).stream().filter(u->u.username().equals("learner06@lookahead.dev")).findFirst().orElseThrow().scenario())
      .isEqualTo("Learn · ending soon · student discount · custom plan · in progress");
  UUID coauthor01=new TestAccountRoster.Entry("coauthor01",true).accountId(),learner07=new TestAccountRoster.Entry("learner07",false).accountId();
  var principal=sessions.actAs(adminSub,learner07);
  assertThat(principal.accountId()).isEqualTo(learner07);assertThat(principal.username()).isEqualTo("learner07@lookahead.dev");
  assertThat(principal.displayName()).isEqualTo("Learner07");assertThat(principal.isEnabled()).isTrue();assertThat(principal.cloudAuthor()).isFalse();
  assertThatThrownBy(()->sessions.actAs(adminSub,coauthor01)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_SESSION_FORBIDDEN"));
  assertThat(sessions.actAs(coauthorSub,coauthor01).accountId()).isEqualTo(coauthor01);
  UUID admin01=new TestAccountRoster.Entry("admin01",true).accountId();
  for(UUID target:List.of(admin01,coauthor01,learner07))for(String below:List.of(learnerSub,"sub-reviewer","sub-tester"))
   assertThatThrownBy(()->sessions.actAs(below,target)).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);
     assertThat(f.code()).isEqualTo("TEST_SESSION_FORBIDDEN");assertThat(f.getMessage()).isEqualTo("Your role cannot use this test account.");});
  assertThatThrownBy(()->sessions.actAs("sub-dual",coauthor01)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_SESSION_FORBIDDEN"));
  assertThat(sessions.start(adminSub,learner07).accountId()).isEqualTo(learner07);
  assertThatThrownBy(()->sessions.start(learnerSub,learner07)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_SESSION_FORBIDDEN"));
  assertThatThrownBy(()->sessions.actAs("sub-waiting",learner07)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_SESSION_FORBIDDEN"));
  assertThatThrownBy(()->sessions.actAs(disabledSub,learner07)).isInstanceOf(AccountFailure.class);
  // DLV-969 S36: a second sign-in linked to the co-author's account (V19 cloud_identities) holds the gate as that account.
  UUID coauthorAccount=jdbc.queryForObject("SELECT account_id FROM cloud_accounts WHERE subject=?",UUID.class,coauthorSub);
  com.lookahead.domain.cloud.CloudIdentities.insert(jdbc,coauthorAccount,ISSUER,"sub-coauthor-google","Google_1001","coauthor@gmail.invalid","linked");
  assertThat(sessions.usable("sub-coauthor-google")).hasSize(35);
  assertThat(sessions.actAs("sub-coauthor-google",coauthor01).accountId()).isEqualTo(coauthor01);
  // A start without an account id is refused before anything is looked up.
  assertThatThrownBy(()->sessions.actAs(adminSub,null)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(422));
  // A real account is never a test-session target, even for the owner.
  UUID real=holder("sub-real",false);
  assertThatThrownBy(()->sessions.actAs(ownerSub,real)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(404));
  // A holder disabled after the session started loses it on the next call.
  jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) SELECT account_id,'disabled',? FROM cloud_accounts WHERE subject=?",owner.accountId(),adminSub);
  assertThatThrownBy(()->sessions.actAs(adminSub,learner07)).isInstanceOf(AccountFailure.class);
  // The Gateway secret is compared on every request.
  var request=new MockHttpServletRequest();assertThat(sessions.fromGateway(request)).isFalse();
  request.addHeader(DevTestSessions.SECRET_HEADER,"synthetic-gateway-secret");assertThat(sessions.fromGateway(request)).isTrue();
  // Outside dev mode nothing answers.
  var off=new DevTestSessions(jdbc,new TestAccountPolicy(TestAccountPolicyTest.cloud("prod")),ISSUER,"synthetic-gateway-secret");
  assertThatThrownBy(()->off.usable(ownerSub)).isInstanceOf(AccountFailure.class);
  assertThatThrownBy(()->off.actAs(ownerSub,learner07)).isInstanceOf(AccountFailure.class);
 }

 /** DLV-969 (owner, 2026-11-04): the session acts as the test account, so its courses are the test account's, not the holder's. */
 @Test void aTestSessionHasTheTestAccountsCourseAccessNotTheHolders(){
  var roster=roster();while(roster.next(owner).remaining()>0){}
  // Learners never switch (owner, 2026-11-04), so an admin holds the gate here.
  UUID holder=holder("sub-admin",false,"admin");
  UUID learner05=new TestAccountRoster.Entry("learner05",false).accountId();
  var principal=sessions().actAs("sub-admin",learner05);
  assertThat(principal.accountId()).isEqualTo(learner05).isNotEqualTo(holder);
  var summaries=new AccessSummaryService(jdbc,json,mock(StaffAccessService.class));
  assertThat(summaries.get(principal,principal.accountId()).labels()).anySatisfy(label->assertThat(label).contains("Learn"));
  assertThat(summaries.get(new AccountPrincipal(holder,"sub-admin","sub-admin",true,false),holder).label()).isEqualTo("Free");
 }
 /** DLV-969 (owner, 2026-11-04): each reader of the test-account list sees what the one hierarchy lets them act on. */
 @Test void theTestAccountListFollowsTheReadersRole(){
  var roster=roster();while(roster.next(owner).remaining()>0){}
  var ownerList=new TestAccountsService(jdbc,json,policy,new ScenarioPurchases(jdbc,json,policy),plans,ownerStaff(),
      new AccessSummaryService(jdbc,json,ownerStaff()),mock(SnapshotValidator.class));
  var coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
  assertThat(ownerList.list(owner)).hasSize(35);
  assertThat(rosterService(Set.of("co-author")).list(coauthor)).hasSize(35);
  assertThat(rosterService(Set.of("admin")).list(admin)).hasSize(33)
      .allSatisfy(row->assertThat(row.get("roles").toString()).doesNotContain("co-author"));
  // Lower role wins: a co-author who is also an admin reads as an admin.
  assertThat(rosterService(Set.of("co-author","admin")).list(admin)).hasSize(33);
  assertThatThrownBy(()->rosterService(Set.of("reviewer")).list(admin)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("STAFF_REQUIRED"));
  // Reset stays owner or admin; an admin never resets a co-author test account.
  UUID coauthor01=new TestAccountRoster.Entry("coauthor01",true).accountId(),admin02=new TestAccountRoster.Entry("admin02",true).accountId();
  var service=rosterService(Set.of("admin"));
  assertThatThrownBy(()->service.reset(admin,coauthor01,new TestAccountsService.Reset(UUID.randomUUID(),revision(coauthor01),"coauthor01@lookahead.dev","scenario")))
      .isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("RESET_FORBIDDEN");});
  assertThatThrownBy(()->rosterService(Set.of("co-author","admin")).reset(admin,coauthor01,new TestAccountsService.Reset(UUID.randomUUID(),revision(coauthor01),"coauthor01@lookahead.dev","scenario")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("RESET_FORBIDDEN"));
  service.reset(admin,admin02,new TestAccountsService.Reset(UUID.randomUUID(),revision(admin02),"admin02@lookahead.dev","scenario"));
  ownerList.reset(owner,coauthor01,new TestAccountsService.Reset(UUID.randomUUID(),revision(coauthor01),"coauthor01@lookahead.dev","scenario"));
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,coauthor01)).isEqualTo("[\"co-author\"]");
 }
 long revision(UUID id){return jdbc.queryForObject("SELECT revision FROM test_account_registry WHERE account_id=?",Long.class,id);}
 /** As DELETE did before roster accounts were permanent: data erased, the registry row deleted, the admitted row kept. */
 void deleteTheOldWay(UUID id){
  new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
   new AccountDataErasure(jdbc).erase(id,false);
   jdbc.update("UPDATE test_account_creations SET state='deleted' WHERE account_id=?",id);
   jdbc.update("UPDATE test_account_registry SET creation_state='deleted',revision=revision+1,last_changed_by=? WHERE account_id=?",owner.accountId().toString(),id);
  });
 }
 /** DLV-969 (owner, 2026-11-04): the owner's Restore of roster accounts deleted before they became permanent. */
 @Test void theOwnerRestoresDeletedRosterAccountsFromTheirScenarioOnlyOnce(){
  var roster=roster();while(roster.next(owner).remaining()>0){}
  var created=this.created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner02",false));
  this.created.delete(admin,created.accountId());
  UUID learner05=new TestAccountRoster.Entry("learner05",false).accountId(),coauthor02=new TestAccountRoster.Entry("coauthor02",true).accountId();
  deleteTheOldWay(learner05);deleteTheOldWay(coauthor02);
  // learner05's admitted row is gone as well; Restore admits it again on the test issuer.
  jdbc.update("DELETE FROM cloud_identities WHERE account_id=?",learner05);
  jdbc.update("UPDATE cloud_accounts SET admitted=false WHERE account_id=?",learner05);jdbc.update("DELETE FROM cloud_accounts WHERE account_id=?",learner05);
  long before=revision(learner05);
  assertThat(roster.statusFor(owner)).extracting("ready","deleted").containsExactly(33,2);
  assertThat(sessions().usable("sub-owner")).isEmpty();
  holder("sub-owner",true);
  assertThat(sessions().usable("sub-owner")).hasSize(33);
  // The owner check is the existing one: 403 OWNER_REQUIRED.
  var catalog=mock(SnapshotValidator.class);
  var realStaff=new StaffAccessService(repository,ownerAccess,catalog);
  var service=new TestAccountsService(jdbc,json,policy,new ScenarioPurchases(jdbc,json,policy),plans,realStaff,new AccessSummaryService(jdbc,json,realStaff),catalog);
  var restoring=new DevTestRoster(jdbc,new DataSourceTransactionManager(source),policy,ownerAccess,service);
  try(var log=com.lookahead.domain.LogCapture.start()){
   assertThatThrownBy(()->restoring.restore(admin,new DevTestRoster.Restore("RESTORE")))
       .isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("OWNER_REQUIRED");});
   assertThat(com.lookahead.domain.LogCapture.keys(log.only("test-account.restore"))).containsEntry("reason","OWNER_REQUIRED").containsEntry("account.id",admin.accountId());
  }
  for(var wrong:Arrays.asList(null,new DevTestRoster.Restore("restore"),new DevTestRoster.Restore(null)))
   assertThatThrownBy(()->restoring.restore(owner,wrong)).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(422);assertThat(f.code()).isEqualTo("ACCOUNT_CONFIRMATION_REQUIRED");});
  assertThatThrownBy(()->restoring.restore(null,new DevTestRoster.Restore("RESTORE"))).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(401));
  assertThatThrownBy(()->new DevTestRoster(jdbc,new DataSourceTransactionManager(source),new TestAccountPolicy(TestAccountPolicyTest.local()),ownerAccess,service)
      .restore(owner,new DevTestRoster.Restore("RESTORE"))).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_ACCOUNTS_DISABLED"));
  assertThat(roster.statusFor(owner).deleted()).isEqualTo(2);
  clearInvocations(plans);
  DevTestRoster.Restored result;
  try(var log=com.lookahead.domain.LogCapture.start()){
   result=restoring.restore(owner,new DevTestRoster.Restore("RESTORE"));
   var lines=log.lines("test-account.restore");
   assertThat(lines).hasSize(2).extracting(line->com.lookahead.domain.LogCapture.keys(line).get("target.id")).containsExactlyInAnyOrder(learner05,coauthor02);
   assertThat(lines).allSatisfy(line->assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("account.id",owner.accountId()));
   log.containsNone("learner05");log.containsNone("coauthor02");
  }
  assertThat(result.restored()).isEqualTo(2);
  assertThat(roster.statusFor(owner)).extracting("total","ready","deleted").containsExactly(35,35,0);
  // Rebuilt like Reset: learner05's Learn membership and its study plan; coauthor02's role.
  assertThat(jdbc.queryForList("SELECT plan_id FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL",String.class,learner05)).containsExactly("learn");
  verify(plans).populate(eq(learner05),argThat(scenario->scenario.number()==5),any());
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,coauthor02)).isEqualTo("[\"co-author\"]");
  assertThat(jdbc.queryForMap("SELECT issuer,admitted,enabled,admitted_by FROM cloud_accounts WHERE account_id=?",learner05))
      .containsEntry("issuer",TestAccountRoster.TEST_ISSUER).containsEntry("admitted",true).containsEntry("enabled",true).containsEntry("admitted_by",owner.accountId());
  for(UUID id:List.of(learner05,coauthor02)){
   assertThat(jdbc.queryForMap("SELECT creation_state,initialization,last_changed_by FROM test_account_registry WHERE account_id=?",id))
       .containsEntry("creation_state","active").containsEntry("initialization","scenario").containsEntry("last_changed_by",owner.accountId().toString());
   assertThat(jdbc.queryForObject("SELECT actor_id FROM test_account_resets WHERE account_id=? AND preset='restore'",UUID.class,id)).isEqualTo(owner.accountId());
  }
  assertThat(revision(learner05)).isGreaterThan(before);
  // A created test account is never restored; the roster is usable again.
  assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,created.accountId())).isEqualTo("deleted");
  assertThat(sessions().usable("sub-owner")).hasSize(35);
  assertThat(sessions().actAs("sub-owner",learner05).accountId()).isEqualTo(learner05);
  // Again: nothing to restore, 200 with 0.
  try(var log=com.lookahead.domain.LogCapture.start()){
   assertThat(restoring.restore(owner,new DevTestRoster.Restore("RESTORE")).restored()).isZero();
   assertThat(com.lookahead.domain.LogCapture.keys(log.only("test-account.restore"))).containsEntry("count",0);
  }
  assertThat(service.restoreRosterEntry(owner,new TestAccountRoster.Entry("learner99",false))).isFalse();
 }

 static JdbcTemplate fixtureJdbc; static org.springframework.transaction.PlatformTransactionManager fixtureTransactions;
 static final String SECRET="synthetic-gateway-secret-for-dev-test-sessions-0001";
 /** The real DEV chains (Domain API, cloud sign-ins, test sessions) on this database; no Cognito call is made. */
 @Test void throughTheDevSecurityChainsATestSessionNeedsTheGatewaySecretAndAnAllowedHolder() throws Exception {
  var roster=roster();while(roster.next(owner).remaining()>0){}
  holder("sub-admin",false,"admin");UUID disabled=holder("sub-disabled",false,"admin");
  jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",disabled,owner.accountId());
  String learner07=new TestAccountRoster.Entry("learner07",false).accountId().toString(),coauthor01=new TestAccountRoster.Entry("coauthor01",true).accountId().toString();
  fixtureJdbc=jdbc;fixtureTransactions=new DataSourceTransactionManager(source);
  try(var context=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()){
   context.setServletContext(new org.springframework.mock.web.MockServletContext());context.getEnvironment().setActiveProfiles("accounts","resource");
   context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("dev-fixture",Map.of(
       "app.deployment-environment","dev","app.test-accounts.identity","dev","app.cognito.issuer",ISSUER,"app.cognito.region","us-east-2",
       "app.cognito.client-id","syntheticclient01","app.cognito.scope-prefix","lookahead","app.cognito.gateway-secret",SECRET)));
   context.register(Application.class);context.refresh();
   var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
   // The Gateway, for a gate opened by an admin, acts as learner07; a write needs no CSRF token, as with a bearer token.
   http.perform(get("/api/v1/auth/me").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,learner07))
       .andExpect(status().isOk()).andExpect(jsonPath("account").value(learner07)).andExpect(header().doesNotExist("Set-Cookie"));
   http.perform(post("/api/v1/plans").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,learner07))
       .andExpect(status().isOk()).andExpect(jsonPath("account").value(learner07));
   // No secret, a wrong one, a bearer token alongside, a role above the holder's, or a disabled holder: refused.
   for(var refused:List.of(get("/api/v1/auth/me").header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,learner07),
       get("/api/v1/auth/me").header(DevTestSessions.SECRET_HEADER,"wrong").header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,learner07),
       get("/api/v1/auth/me").header("Authorization","Bearer x").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,learner07),
       get("/api/v1/auth/me").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,coauthor01),
       get("/api/v1/auth/me").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-disabled").header(DevTestSessions.ACCOUNT_HEADER,learner07),
       post("/api/v1/plans").header(DevTestSessions.HOLDER_HEADER,"sub-admin").header(DevTestSessions.ACCOUNT_HEADER,learner07),
       get("/api/v1/auth/me")))
    http.perform(refused).andExpect(status().isUnauthorized());
   // The internal routes answer only the Gateway.
   http.perform(get("/internal/v1/test-sessions/accounts").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin"))
       .andExpect(status().isOk()).andExpect(jsonPath("data.length()").value(33));
   http.perform(get("/internal/v1/test-sessions/accounts").header(DevTestSessions.HOLDER_HEADER,"sub-admin")).andExpect(status().isUnauthorized());
   http.perform(post("/internal/v1/test-sessions/start").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin")
       .contentType("application/json").content("{\"accountId\":\""+learner07+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("data.username").value("learner07@lookahead.dev"));
   http.perform(post("/internal/v1/test-sessions/start").header(DevTestSessions.SECRET_HEADER,SECRET).header(DevTestSessions.HOLDER_HEADER,"sub-admin")
       .contentType("application/json").content("{\"accountId\":\""+coauthor01+"\"}")).andExpect(status().isForbidden());
   http.perform(post("/internal/v1/test-sessions/start").header(DevTestSessions.HOLDER_HEADER,"sub-admin")
       .contentType("application/json").content("{\"accountId\":\""+learner07+"\"}")).andExpect(status().isUnauthorized());
  }
 }
 @org.springframework.context.annotation.Configuration @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
 @org.springframework.web.servlet.config.annotation.EnableWebMvc
 @org.springframework.context.annotation.Import({com.lookahead.domain.security.DomainSecurityConfiguration.class,com.lookahead.domain.cloud.CloudSecurityConfiguration.class,
     DevTestSessionConfiguration.class,DevTestSessionsController.class,TestAccountPolicy.class,com.lookahead.learning.content.handler.AccountErrorHandler.class,Probe.class})
 static class Application {
  @org.springframework.context.annotation.Bean JdbcTemplate jdbc(){return fixtureJdbc;}
  @org.springframework.context.annotation.Bean org.springframework.transaction.PlatformTransactionManager transactions(){return fixtureTransactions;}
 }
 @org.springframework.web.bind.annotation.RestController static class Probe {
  @org.springframework.web.bind.annotation.GetMapping("/api/v1/auth/me") Map<String,String> who(@org.springframework.security.core.annotation.AuthenticationPrincipal AccountPrincipal principal){return Map.of("account",principal.accountId().toString());}
  @org.springframework.web.bind.annotation.PostMapping("/api/v1/plans") Map<String,String> write(@org.springframework.security.core.annotation.AuthenticationPrincipal AccountPrincipal principal){return Map.of("account",principal.accountId().toString());}
 }
}
