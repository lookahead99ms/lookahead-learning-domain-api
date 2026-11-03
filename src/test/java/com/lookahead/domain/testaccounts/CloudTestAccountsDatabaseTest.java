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
 * DLV-969 S35 against the disposable cloud-auth database: the full Domain schema (V1-V15, V17, V18) in a fresh schema,
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
  for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql","V11__free_plan_allowances.sql","V12__created_test_accounts.sql","V13__real_account_maintenance.sql","V14__managed_test_purchase_purge_grants.sql","V15__retain_runtime_audit.sql","V17__dev_access_admission.sql","V18__access_removal_and_staff_aliases.sql"))
   jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
  for(var actor:List.of(admin,owner))jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",actor.accountId());
 }
 @BeforeEach void service(){
  // Every test starts without test accounts or their purchases, plans and grants; as in DEV, audit rows stay.
  var erasure=new AccountDataErasure(jdbc);var transactions=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
  for(UUID id:jdbc.queryForList("SELECT account_id FROM test_account_registry",UUID.class))transactions.executeWithoutResult(status->erasure.erase(id,false));
  jdbc.update("DELETE FROM cloud_sign_ins");jdbc.update("DELETE FROM cloud_account_states");
  jdbc.update("DELETE FROM test_account_registry");jdbc.update("DELETE FROM test_account_creations");
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
 @Test void scenariosFollowTheCreatorsRoleAndTheAllowanceListsOnlyThoseAllowed(){
  assertThatThrownBy(()->created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"co-author",false)))
      .isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("SCENARIO_FORBIDDEN");});
  assertThatThrownBy(()->created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner99",false)))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("UNKNOWN_SCENARIO"));
  var staffAccount=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"admin",false));
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,staffAccount.accountId())).isEqualTo("[\"admin\"]");
  var ids=created.allowance(admin).scenarios().stream().map(TestAccountTemplates.Template::id).toList();
  assertThat(ids).hasSize(28).contains("learner01","learner27","admin").doesNotContain("co-author");
  assertThat(created.allowance(owner).scenarios()).hasSize(29);
  assertThat(created.allowance(owner).limit()).isEqualTo(Integer.MAX_VALUE);
  // Nothing was reserved by a refused request.
  assertThat(created.allowance(admin).used()).isEqualTo(1);
 }
 @Test void deleteKeepsTheAdmittedRowAndRemovesTheAccountFromEveryTestSession(){
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),null,"learner02",false));
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:core-java')",learner.accountId());
  String ownerSub="sub-owner";holder(ownerSub,true);
  assertThat(sessions().authenticate(ownerSub,learner.accountId()).username()).isEqualTo(learner.username());
  created.delete(admin,learner.accountId());
  assertThat(jdbc.queryForMap("SELECT admitted,enabled FROM cloud_accounts WHERE account_id=?",learner.accountId())).containsEntry("admitted",true).containsEntry("enabled",true);
  assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,learner.accountId())).isEqualTo("deleted");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE account_id=?",Integer.class,learner.accountId())).isZero();
  assertThatThrownBy(()->sessions().authenticate(ownerSub,learner.accountId())).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(404));
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
  assertThat(roster.statusFor(owner)).isEqualTo(new DevTestRoster.Status(35,0,0));
  assertThat(roster.statusFor(admin)).isNull();
  assertThatThrownBy(()->roster.next(admin)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_ROSTER_OWNER_ONLY"));
  assertThat(roster.next(owner)).isEqualTo(new DevTestRoster.Status(35,5,0));
  int calls=1;DevTestRoster.Status status;
  do{status=roster.next(owner);calls++;}while(status.remaining()>0);
  assertThat(calls).isEqualTo(7);assertThat(status).isEqualTo(new DevTestRoster.Status(35,35,0));
  assertThat(roster.next(owner)).isEqualTo(status);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE issuer=? AND admitted AND admitted_by=?",Integer.class,TestAccountRoster.TEST_ISSUER,owner.accountId())).isEqualTo(35);
  var learner01=TestAccountRoster.entries().getFirst();
  assertThat(jdbc.queryForMap("SELECT username,display_name FROM cloud_accounts WHERE account_id=?",learner01.accountId()))
      .containsEntry("username","learner01@lookahead.dev").containsEntry("display_name","Learner01");
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,new TestAccountRoster.Entry("coauthor01",true).accountId())).isEqualTo("[\"co-author\"]");
  assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,new TestAccountRoster.Entry("admin06",true).accountId())).isEqualTo("[\"admin\"]");
  // learner12: Advance ending soon with Grow scheduled next.
  var learner12=new TestAccountRoster.Entry("learner12",false).accountId();
  assertThat(jdbc.queryForList("SELECT plan_id FROM membership_purchases WHERE account_id=? ORDER BY starts_at",String.class,learner12)).containsExactly("advance","grow");
  assertThat(rosterService(Set.of("admin")).list(admin)).hasSize(35);
  // Reset in DEV: only to the scenario, confirmed with the username the list shows.
  var service=rosterService(Set.of("admin"));
  long revision=jdbc.queryForObject("SELECT revision FROM test_account_registry WHERE account_id=?",Long.class,learner12);
  assertThatThrownBy(()->service.reset(admin,learner12,new TestAccountsService.Reset(UUID.randomUUID(),revision,"learner12","scenario"))).hasMessageContaining("Confirm");
  assertThatThrownBy(()->service.reset(admin,learner12,new TestAccountsService.Reset(UUID.randomUUID(),revision,"learner12@lookahead.dev","legacy")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("RESET_UNAVAILABLE"));
  service.reset(admin,learner12,new TestAccountsService.Reset(UUID.randomUUID(),revision,"learner12@lookahead.dev","scenario"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL",Integer.class,learner12)).isEqualTo(2);
  // A deleted roster account is never created again.
  created.delete(owner,learner01.accountId());
  assertThat(roster.next(owner)).isEqualTo(new DevTestRoster.Status(35,34,1));
  assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,learner01.accountId())).isEqualTo("deleted");
 }
 @Test void testSessionsFollowTheGateHoldersRoleAndStateOnEveryCall(){
  var roster=roster();while(roster.next(owner).remaining()>0){}
  String ownerSub="sub-owner",coauthorSub="sub-coauthor",adminSub="sub-admin",learnerSub="sub-learner",disabledSub="sub-disabled";
  holder(ownerSub,true);holder(coauthorSub,false,"co-author");holder(adminSub,false,"admin");holder(learnerSub,false);
  UUID disabled=holder(disabledSub,false,"admin");
  jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",disabled,owner.accountId());
  var sessions=sessions();
  assertThat(sessions.usable(ownerSub)).hasSize(35);
  assertThat(sessions.usable(coauthorSub)).hasSize(35);
  assertThat(sessions.usable(adminSub)).hasSize(33).noneMatch(u->u.roles().contains("co-author"));
  for(String nobody:new String[]{learnerSub,disabledSub,"sub-unknown","",null})assertThat(sessions.usable(nobody)).as(String.valueOf(nobody)).isEmpty();
  var first=sessions.usable(adminSub).getFirst();
  assertThat(first.username()).isEqualTo("admin01@lookahead.dev");assertThat(first.roles()).containsExactly("admin");
  assertThat(sessions.usable(ownerSub).stream().filter(u->u.username().equals("learner06@lookahead.dev")).findFirst().orElseThrow().scenario())
      .isEqualTo("Learn · ending soon · student discount · custom plan · in progress");
  UUID coauthor01=new TestAccountRoster.Entry("coauthor01",true).accountId(),learner07=new TestAccountRoster.Entry("learner07",false).accountId();
  var principal=sessions.authenticate(adminSub,learner07);
  assertThat(principal.accountId()).isEqualTo(learner07);assertThat(principal.username()).isEqualTo("learner07@lookahead.dev");
  assertThat(principal.displayName()).isEqualTo("Learner07");assertThat(principal.isEnabled()).isTrue();assertThat(principal.cloudAuthor()).isFalse();
  assertThatThrownBy(()->sessions.authenticate(adminSub,coauthor01)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_SESSION_FORBIDDEN"));
  assertThat(sessions.authenticate(coauthorSub,coauthor01).accountId()).isEqualTo(coauthor01);
  assertThatThrownBy(()->sessions.authenticate(learnerSub,learner07)).isInstanceOf(AccountFailure.class);
  assertThatThrownBy(()->sessions.authenticate(disabledSub,learner07)).isInstanceOf(AccountFailure.class);
  // A real account is never a test-session target, even for the owner.
  UUID real=holder("sub-real",false);
  assertThatThrownBy(()->sessions.authenticate(ownerSub,real)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(404));
  // A holder disabled after the session started loses it on the next call.
  jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) SELECT account_id,'disabled',? FROM cloud_accounts WHERE subject=?",owner.accountId(),adminSub);
  assertThatThrownBy(()->sessions.authenticate(adminSub,learner07)).isInstanceOf(AccountFailure.class);
  // The Gateway secret is compared on every request.
  var request=new MockHttpServletRequest();assertThat(sessions.fromGateway(request)).isFalse();
  request.addHeader(DevTestSessions.SECRET_HEADER,"synthetic-gateway-secret");assertThat(sessions.fromGateway(request)).isTrue();
  // Outside dev mode nothing answers.
  var off=new DevTestSessions(jdbc,new TestAccountPolicy(TestAccountPolicyTest.cloud("prod")),ISSUER,"synthetic-gateway-secret");
  assertThatThrownBy(()->off.usable(ownerSub)).isInstanceOf(AccountFailure.class);
  assertThatThrownBy(()->off.authenticate(ownerSub,learner07)).isInstanceOf(AccountFailure.class);
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
