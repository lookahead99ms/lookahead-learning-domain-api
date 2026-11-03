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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 phase C against the disposable cloud-auth database: the full Domain schema (V1-V15, V17) in a
 * fresh schema, the Cognito operator mocked, the policy in dev + cognito mode.
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudTestAccountsDatabaseTest {
 static final String ISSUER="https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
 JdbcTemplate jdbc; DriverManagerDataSource source; String schema; ObjectMapper json=new ObjectMapper();
 AccountPrincipal admin=new AccountPrincipal(UUID.randomUUID(),"operator","Operator",true,false);
 TestAccountPolicy policy=new TestAccountPolicy(TestAccountPolicyTest.cloud("dev"));
 CognitoTestIdentityOperator operator; StaffAccessRepository repository; CreatedTestAccounts created;
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
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",admin.accountId());
 }
 @BeforeEach void service(){
  jdbc.update("DELETE FROM cloud_sign_ins");jdbc.update("DELETE FROM test_account_registry");jdbc.update("DELETE FROM test_account_creations");
  jdbc.update("UPDATE cloud_accounts SET admitted=false");jdbc.update("DELETE FROM cloud_accounts");
  operator=mock(CognitoTestIdentityOperator.class);when(operator.issuer()).thenReturn(ISSUER);
  when(operator.provision(any(),any(),any())).thenAnswer(call->"sub-"+call.getArgument(1,String.class));
  repository=mock(StaffAccessRepository.class);
  when(repository.forAccount(admin.accountId())).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(),admin.accountId(),null,Set.of("admin"),Set.of(),false,1));
  created=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(source),policy,repository,mock(LocalAuthorAccess.class),
      mock(TestIdentityOperator.class),provider(operator));
 }
 @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 static ObjectProvider<CognitoTestIdentityOperator> provider(CognitoTestIdentityOperator operator){
  var beans=new StaticListableBeanFactory();if(operator!=null)beans.addBean("cognitoTestIdentityOperator",operator);
  return beans.getBeanProvider(CognitoTestIdentityOperator.class);
 }
 TestAccountsService rosterService(Set<String> roles){
  var staff=mock(StaffAccessService.class);when(staff.permissions(any())).thenReturn(StaffPermissions.forRoles(false,roles));
  var catalog=mock(SnapshotValidator.class);when(catalog.allTopicIds()).thenReturn(Set.of("learn:hands-on-dsa"));
  return new TestAccountsService(jdbc,json,policy,new ScenarioPurchases(jdbc,json,policy),mock(ScenarioPlans.class),staff,new AccessSummaryService(jdbc,json,staff),catalog);
 }
 @Test void createInCloudModeWritesTheAdmittedRowAndItsAuditEventOnceAndIsRetrySafe(){
  var key=UUID.randomUUID();var request=new CreatedTestAccounts.Create(key,"Synthetic-learner-password-1!");
  var first=created.create(admin,request);
  assertThat(first.active()).isTrue();
  // The Gateway's sign-in form takes an email: a reserved .test address that fits the varchar(32) columns.
  assertThat(first.username()).matches("test-[0-9a-f]{10}@lookahead\\.test").hasSizeLessThanOrEqualTo(32);
  var row=jdbc.queryForMap("SELECT issuer,subject,admitted,enabled,author_access,username,display_name,admitted_by,admitted_at,requested_at,verified_email FROM cloud_accounts WHERE account_id=?",first.accountId());
  assertThat(row.get("issuer")).isEqualTo(ISSUER);assertThat(row.get("subject")).isEqualTo("sub-"+first.username());
  assertThat(row.get("admitted")).isEqualTo(true);assertThat(row.get("enabled")).isEqualTo(true);assertThat(row.get("author_access")).isEqualTo(false);
  assertThat(row.get("username")).isEqualTo(first.username());assertThat(row.get("display_name")).isEqualTo(first.username());
  assertThat(row.get("admitted_by")).isEqualTo(admin.accountId());assertThat(row.get("admitted_at")).isNotNull();assertThat(row.get("verified_email")).isNull();
  var event=jdbc.queryForMap("SELECT action,actor_id,issuer,subject,roles::text AS roles FROM cloud_admission_events WHERE account_id=?",first.accountId());
  assertThat(event.get("action")).isEqualTo("admit");assertThat(event.get("actor_id")).isEqualTo(admin.accountId());
  assertThat(event.get("issuer")).isEqualTo(ISSUER);assertThat(event.get("subject")).isEqualTo("sub-"+first.username());assertThat(event.get("roles")).isEqualTo("[]");
  assertThat(jdbc.queryForMap("SELECT initialization,creator_id,creation_state FROM test_account_registry WHERE account_id=?",first.accountId()))
      .containsEntry("initialization","created").containsEntry("creator_id",admin.accountId()).containsEntry("creation_state","active");
  // Same key again: no second provisioning, no second row or event; the allowance is spent once.
  assertThat(created.create(admin,request).accountId()).isEqualTo(first.accountId());
  verify(operator,times(1)).provision(eq(first.accountId()),eq(first.username()),eq("Synthetic-learner-password-1!"));
  verify(operator,times(2)).activate(first.accountId(),first.username());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=?",Integer.class,first.accountId())).isEqualTo(1);
  assertThat(created.allowance(admin)).isEqualTo(new CreatedTestAccounts.Allowance(10,1,9));
  // The waiting list never shows a created learner: it is admitted on creation.
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE NOT admitted",Integer.class)).isZero();
 }
 @Test void provisioningFailureOrMissingSubjectLeavesARetryableReservationWithoutDomainRows(){
  var key=UUID.randomUUID();var request=new CreatedTestAccounts.Create(key,"Synthetic-learner-password-1!");
  when(operator.provision(any(),any(),any())).thenThrow(TestIdentityOperator.unavailable()).thenReturn(" ").thenAnswer(call->"sub-"+call.getArgument(1,String.class));
  assertThatThrownBy(()->created.create(admin,request)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_IDENTITY_UNAVAILABLE"));
  assertThatThrownBy(()->created.create(admin,request)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_IDENTITY_UNAVAILABLE"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts",Integer.class)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry",Integer.class)).isZero();
  assertThat(jdbc.queryForObject("SELECT state FROM test_account_creations WHERE creator_id=? AND mutation_key=?",String.class,admin.accountId(),key)).isEqualTo("pending");
  assertThat(created.allowance(admin).used()).isZero();
  var completed=created.create(admin,request);
  assertThat(completed.active()).isTrue();
  assertThat(jdbc.queryForObject("SELECT admitted FROM cloud_accounts WHERE account_id=?",Boolean.class,completed.accountId())).isTrue();
  assertThat(created.allowance(admin).used()).isEqualTo(1);
  // Without an operator bean the cloud mode is unavailable before any reservation is spent.
  var unwired=new CreatedTestAccounts(jdbc,new DataSourceTransactionManager(source),policy,repository,mock(LocalAuthorAccess.class),mock(TestIdentityOperator.class),provider(null));
  assertThatThrownBy(()->unwired.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),"Synthetic-learner-password-1!")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.status()).isEqualTo(503));
  assertThat(created.allowance(admin).used()).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_creations WHERE creator_id=? AND state='pending'",Integer.class,admin.accountId())).isZero();
 }
 @Test void deleteRevokesSignInsDeletesTheCognitoUserAndKeepsTheAdmittedRow(){
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),"Synthetic-learner-password-1!"));
  UUID signIn=UUID.randomUUID();
  jdbc.update("INSERT INTO cloud_sign_ins(id,account_id,family_digest,binding_digest,created_at,last_active_at,recent_auth_at) VALUES (?,?,?,?,now(),now(),now())",
      signIn,learner.accountId(),"a".repeat(64),"b".repeat(64));
  jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:core-java')",learner.accountId());
  created.delete(admin,learner.accountId());
  verify(operator).disable(learner.accountId(),learner.username());
  assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM cloud_sign_ins WHERE id=?",Boolean.class,signIn)).isTrue();
  assertThat(jdbc.queryForMap("SELECT admitted,enabled FROM cloud_accounts WHERE account_id=?",learner.accountId())).containsEntry("admitted",true).containsEntry("enabled",true);
  assertThat(jdbc.queryForObject("SELECT creation_state FROM test_account_registry WHERE account_id=?",String.class,learner.accountId())).isEqualTo("deleted");
  assertThat(jdbc.queryForObject("SELECT state FROM test_account_creations WHERE account_id=?",String.class,learner.accountId())).isEqualTo("deleted");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE account_id=?",Integer.class,learner.accountId())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=?",Integer.class,learner.accountId())).isEqualTo(1);
  // The lifetime allowance counts the deleted learner, as in Local.
  assertThat(created.allowance(admin)).isEqualTo(new CreatedTestAccounts.Allowance(10,1,9));
  assertThatThrownBy(()->created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),"short"))).hasMessageContaining("15 to 72");
 }
 /** DLV-969 (owner decision 2026-11-03): a co-author sees who created or changed a test account as a role label. */
 @Test void onlyTheOwnerSeesWhoCreatedATestAccount(){
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),"Synthetic-learner-password-1!"));
  var coauthor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co",true,false);
  var aliases=mock(StaffAliases.class);when(aliases.label(admin.accountId())).thenReturn("Admin 1");
  var service=rosterService(Set.of("co-author"));service.configureAliases(aliases);
  var seen=service.list(coauthor).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow();
  assertThat(seen.get("creatorId").asString()).isEqualTo("Admin 1");
  assertThat(seen.get("lastChangedBy").asString()).isEqualTo("Admin 1");
  // The creator keeps their own id (the page says "You"), and an unreadable value is never shown as it is.
  var own=service.list(admin).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow();
  assertThat(own.get("creatorId").asString()).isEqualTo(admin.accountId().toString());
  jdbc.update("UPDATE test_account_registry SET last_changed_by='not-an-account' WHERE account_id=?",learner.accountId());
  assertThat(service.list(coauthor).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow()
      .get("lastChangedBy").asString()).isEqualTo("Unknown");
  // The owner sees the ids, which the page turns into names.
  when(aliases.seesIdentities(coauthor)).thenReturn(true);
  assertThat(service.list(coauthor).stream().filter(row->row.get("accountId").asString().equals(learner.accountId().toString())).findFirst().orElseThrow()
      .get("creatorId").asString()).isEqualTo(admin.accountId().toString());
  verify(aliases,never()).label(learner.accountId());
 }
 @Test void cloudModeListsOnlyCreatedLearnersAndRefusesTheRoster(){
  var service=rosterService(Set.of("admin"));
  var learner=created.create(admin,new CreatedTestAccounts.Create(UUID.randomUUID(),"Synthetic-learner-password-1!"));
  // A roster-shaped row (what seed() writes in Local) must stay invisible in cloud mode.
  UUID roster=TestAccountRoster.entries().getFirst().accountId();
  jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",roster);
  jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,?,'scenario','seed')",roster,"learner01");
  var listed=service.list(admin);
  assertThat(listed).hasSize(1);
  assertThat(listed.getFirst().get("accountId").asString()).isEqualTo(learner.accountId().toString());
  assertThat(listed.getFirst().get("initialization").asString()).isEqualTo("created");
  assertThat(listed.getFirst().get("legacyPresetAvailable").asBoolean()).isFalse();
  assertThat(listed.getFirst().get("creatorId").asString()).isEqualTo(admin.accountId().toString());
  assertThatThrownBy(service::seed).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(403);assertThat(f.code()).isEqualTo("TEST_ACCOUNTS_DISABLED");});
  assertThatThrownBy(()->service.reset(admin,roster,new TestAccountsService.Reset(UUID.randomUUID(),1,"learner01","scenario")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_ACCOUNTS_DISABLED"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_registry",Integer.class)).isEqualTo(2);
  assertThatThrownBy(()->rosterService(Set.of()).list(admin)).hasMessageContaining("Owner, admin or co-author");
  // The Local-only real-account deletion stays off in cloud mode.
  var maintenance=new RealAccountMaintenance(jdbc,mock(StaffAccessService.class),mock(AccountDirectory.class),policy,mock(TestIdentityOperator.class),new DataSourceTransactionManager(source));
  assertThatThrownBy(()->maintenance.deleteLearner(admin,UUID.randomUUID(),new RealAccountMaintenance.Confirmation("DELETE")))
      .isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("TEST_ACCOUNTS_DISABLED"));
 }
}
