package com.lookahead.domain.cloud;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import com.lookahead.domain.access.AccountAdmissionService;
import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.domain.access.StaffAccessService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudSignInRegistryDatabaseTest {
    JdbcTemplate jdbc;DriverManagerDataSource source;String schema;CloudSignInRegistry registry;
    String issuer="https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
    @BeforeAll void start()throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        schema="cloud_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));admin.execute("CREATE SCHEMA "+schema);
        if(admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='lookahead_platform_app'",Integer.class)==0)admin.execute("CREATE ROLE lookahead_platform_app NOLOGIN");
        source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO lookahead_platform_app");
        jdbc.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON platform_subjects TO lookahead_platform_app");
        jdbc.execute("CREATE FUNCTION reject_author_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'append-only'; END; $$");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V4__cloud_identity_and_sign_ins.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V6__staff_access.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V7__cloud_account_preferences.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V17__dev_access_admission.sql")));
        registry=service();
    }
    CloudSignInRegistry service(){return new CloudSignInRegistry(jdbc,new DataSourceTransactionManager(source));}
    @AfterAll void stop(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    CognitoIdentity identity(String subject){return new CognitoIdentity(issuer,subject,UUID.randomUUID().toString(),"same@example.invalid","Learner",Instant.now());}
    UUID provision(CognitoIdentity identity){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects VALUES(?)",id);jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name) VALUES(?,?,?,true,?,?)",id,identity.issuer(),identity.subject(),identity.username(),identity.displayName());return id;}
    String proof(){return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void preferencesAndLinksPersistAcrossInstancesAndRemainAccountBound() {
        var a=identity("profile-preferences");var b=identity("other-profile-preferences");provision(a);provision(b);
        String ap=proof(),bp=proof();registry.admit(a,ap);registry.admit(b,bp);
        assertThat(registry.profile(a,ap,null).get("themePreference")).isEqualTo("system");
        assertThat(registry.preferences(a,ap,"dark").get("themePreference")).isEqualTo("dark");
        var links=new LinkedHashMap<String,String>();
        links.put("github","https://github.com/synthetic-learner");links.put("linkedin",null);links.put("x",null);links.put("website","https://example.org/about");
        assertThat(registry.links(a,ap,links).get("links")).isEqualTo(links);
        assertThat(service().profile(a,ap,null).get("links")).isEqualTo(links);
        assertThat(service().profile(a,ap,"Changed name").get("themePreference")).isEqualTo("dark");
        assertThat(registry.profile(b,bp,null).get("themePreference")).isEqualTo("system");
        assertThat(registry.profile(b,bp,null).get("links")).isNotEqualTo(links);
        assertThatThrownBy(()->registry.preferences(a,bp,"light")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.preferences(a,ap,"unknown")).isInstanceOf(RuntimeException.class);
        var invalid=new LinkedHashMap<>(links);invalid.put("github","https://github.com.evil.invalid/account");
        assertThatThrownBy(()->registry.links(a,ap,invalid)).isInstanceOf(RuntimeException.class);
        assertThat(service().profile(a,ap,null).get("links")).isEqualTo(links);
        registry.terminate(a,ap);
        assertThatThrownBy(()->registry.links(a,ap,links)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.preferences(a,ap,"light")).isInstanceOf(RuntimeException.class);
    }
    @Test void mapsByIssuerSubjectNotEmailAndRequiresAdmission(){
        var a=identity("first");var b=identity("second");var aid=provision(a);var bid=provision(b);String ap=proof(),bp=proof();
        registry.admit(a,ap);registry.admit(b,bp);
        assertThat(registry.authenticate(a,ap).accountId()).isEqualTo(aid).isNotEqualTo(bid);
        assertThatThrownBy(()->registry.authenticate(a,bp)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.admit(identity("not-invited"),proof())).isInstanceOf(RuntimeException.class);
        assertThat(registry.authenticate(a,ap).cloudAuthor()).isFalse();
        jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE account_id=?",aid);
        assertThat(registry.authenticate(a,ap).cloudAuthor()).isTrue();
    }
    @Test void concurrentAdmissionsAcrossInstancesNeverExceedTwo()throws Exception {
        var first=identity("race");var owner=provision(first);var identities=List.of(first,identity("race"),identity("race"));
        try(var pool=Executors.newFixedThreadPool(3)){
            var latch=new CountDownLatch(1);var tasks=new ArrayList<Future<CloudSignInRegistry.Admission>>();
            for(var id:identities)tasks.add(pool.submit(()->{latch.await();return service().admit(id,proof());}));latch.countDown();
            var admissions=new ArrayList<CloudSignInRegistry.Admission>();for(var t:tasks)admissions.add(t.get(10,TimeUnit.SECONDS));
            assertThat(admissions.stream().filter(a->a.signInId()!=null).count()).isEqualTo(2);
            assertThat(admissions.stream().filter(a->a.challengeToken()!=null).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_sign_ins WHERE account_id=? AND revoked_at IS NULL",Integer.class,owner)).isEqualTo(2);
        }
    }
    @Test void restrictedReplacementIsOwnerBoundIdempotentAndDurable(){
        var a=identity("replace");provision(a);var b=identity("replace");var c=identity("replace");String ap=proof(),bp=proof(),cp=proof();
        var first=registry.admit(a,ap);registry.admit(b,bp);var challenge=registry.admit(c,cp);
        assertThatThrownBy(()->registry.authenticate(c,cp)).isInstanceOf(RuntimeException.class);
        assertThat(registry.challenge(c,cp,challenge.challengeToken()).entries()).hasSize(2);
        assertThatThrownBy(()->registry.replace(c,ap,challenge.challengeToken(),first.signInId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.replace(c,cp,challenge.challengeToken(),UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        var replaced=registry.replace(c,cp,challenge.challengeToken(),first.signInId());
        assertThat(service().replace(c,cp,challenge.challengeToken(),first.signInId()).signInId()).isEqualTo(replaced.signInId());
        assertThatThrownBy(()->registry.authenticate(a,ap)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.admit(a,ap)).isInstanceOf(RuntimeException.class);
        assertThat(service().authenticate(c,cp)).isNotNull();
    }
    @Test void revocationDisableIdleExpiryAndFreshProofAreChecked(){
        var a=identity("state");var owner=provision(a);String p=proof();var signIn=registry.admit(a,p);
        assertThatThrownBy(()->registry.authenticate(a,proof())).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE account_id=?",owner);assertThatThrownBy(()->registry.authenticate(a,p)).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE cloud_accounts SET enabled=true WHERE account_id=?",owner);
        jdbc.update("UPDATE cloud_sign_ins SET recent_auth_at=clock_timestamp()-interval '6 minutes' WHERE id=?",signIn.signInId());
        assertThatThrownBy(()->registry.revokeOthers(a,p)).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE cloud_sign_ins SET last_active_at=clock_timestamp()-interval '31 minutes' WHERE id=?",signIn.signInId());
        assertThatThrownBy(()->registry.authenticate(a,p)).isInstanceOf(RuntimeException.class);
        var old=new CognitoIdentity(issuer,a.subject(),UUID.randomUUID().toString(),a.username(),a.displayName(),Instant.now().minusSeconds(301));
        assertThatThrownBy(()->registry.admit(old,p)).isInstanceOf(RuntimeException.class);
    }
    @Test void cancelAndExpiryCannotGrantAccess(){
        var a=identity("cancel");provision(a);var b=identity("cancel");var c=identity("cancel");String cp=proof();var first=registry.admit(a,proof());registry.admit(b,proof());var challenge=registry.admit(c,cp);
        registry.cancel(c,cp,challenge.challengeToken());assertThatThrownBy(()->registry.replace(c,cp,challenge.challengeToken(),first.signInId())).isInstanceOf(RuntimeException.class);
        var d=identity("cancel");String dp=proof();var expired=registry.admit(d,dp);jdbc.update("UPDATE cloud_sign_in_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE token_digest=?",CloudSignInRegistry.digest(expired.challengeToken()));
        assertThatThrownBy(()->registry.replace(d,dp,expired.challengeToken(),first.signInId())).isInstanceOf(RuntimeException.class);
    }
    @Test void refreshFamilyPreservesSessionAndReauthenticationRetiresOldFamily(){
        var a=identity("refresh");provision(a);String p=proof();var original=registry.admit(a,p);
        var refresh=new CognitoIdentity(issuer,a.subject(),a.family(),a.username(),a.displayName(),a.authenticatedAt());assertThat(service().authenticate(refresh,p)).isNotNull();
        var fresh=identity("refresh");var replacement=registry.admit(fresh,p);assertThat(replacement.signInId()).isNotEqualTo(original.signInId());
        assertThatThrownBy(()->registry.admit(a,p)).isInstanceOf(RuntimeException.class);assertThat(registry.inventory(fresh,p).entries()).hasSize(1);
        registry.terminate(fresh,p);assertThatThrownBy(()->service().authenticate(fresh,p)).isInstanceOf(RuntimeException.class);
    }
    @Test void applicationRoleCannotElevateEnabledOrAuthor(){
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','enabled','UPDATE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','author_access','UPDATE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','issuer','UPDATE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','subject','UPDATE')",Boolean.class)).isFalse();
    }
    /** V17 (DLV-969): the application records first contacts, admits waiting rows and removes expired ones; nothing more. */
    @Test void applicationRoleHoldsExactlyTheAdmissionGrants(){
        for(String privilege:List.of("SELECT","INSERT","DELETE"))assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_accounts',?)",Boolean.class,privilege)).as(privilege).isTrue();
        for(String column:List.of("admitted","admitted_at","admitted_by","requested_at","verified_email","username","display_name","credential_change_pending"))
            assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts',?,'UPDATE')",Boolean.class,column)).as(column).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_accounts','UPDATE')",Boolean.class)).isFalse();
        for(String privilege:List.of("SELECT","INSERT"))assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_admission_events',?)",Boolean.class,privilege)).as(privilege).isTrue();
        for(String privilege:List.of("UPDATE","DELETE","TRUNCATE"))assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_admission_events',?)",Boolean.class,privilege)).as(privilege).isFalse();
    }
    @Test void explicitAdministrationKeepsStableIdentityAndDisableNeverRevivesOldSessions(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        var a=identity("operator-owner");String[] admission={"admit",issuer,a.subject(),a.username(),a.displayName()};
        UUID owner=CloudAccountAdministration.apply(jdbc,tx,admission);
        assertThat(CloudAccountAdministration.apply(jdbc,tx,admission)).isEqualTo(owner);
        assertThat(CloudAccountAdministration.apply(jdbc,tx,new String[]{"admit",issuer,"different-provider-subject",a.username(),a.displayName()})).isNotEqualTo(owner);
        String proof=proof();registry.admit(a,proof);
        assertThat(registry.authenticate(a,proof).cloudAuthor()).isFalse();
        CloudAccountAdministration.apply(jdbc,tx,new String[]{"author-on",issuer,a.subject()});
        assertThat(registry.authenticate(a,proof).cloudAuthor()).isTrue();
        CloudAccountAdministration.apply(jdbc,tx,new String[]{"disable",issuer,a.subject()});
        CloudAccountAdministration.apply(jdbc,tx,new String[]{"enable",issuer,a.subject()});
        assertThatThrownBy(()->registry.authenticate(a,proof)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.admit(a,proof)).isInstanceOf(RuntimeException.class);
        var fresh=identity(a.subject());registry.admit(fresh,proof);
        assertThat(registry.authenticate(fresh,proof).accountId()).isEqualTo(owner);
        assertThatThrownBy(()->CloudAccountAdministration.apply(jdbc,tx,new String[]{"admit",issuer,a.subject()})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void runtimeRoleCanEnforceSessionsAndEditProfileWithoutIdentityAdministration(){
        var identity=identity("runtime-role");provision(identity);String proof=proof();
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        tx.execute(status->{
            jdbc.execute("SET LOCAL ROLE lookahead_platform_app");
            registry.admit(identity,proof);
            assertThat(registry.authenticate(identity,proof)).isNotNull();
            assertThat(registry.profile(identity,proof,"Runtime Learner")).containsEntry("displayName","Runtime Learner");
            registry.terminate(identity,proof);return null;
        });
        assertThatThrownBy(()->registry.authenticate(identity,proof)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE issuer=? AND subject=?",issuer,identity.subject());return null;}))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE issuer=? AND subject=?",issuer,identity.subject());return null;}))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    // ---- DLV-969: first contact, owner admission and the 48-hour cleanup ----
    CognitoIdentity contact(String subject,String email){return new CognitoIdentity(issuer,subject,UUID.randomUUID().toString(),subject+"@example.invalid","Waiting "+subject,Instant.now(),email);}
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status,String code){
        assertThatThrownBy(call).isInstanceOfSatisfying(com.lookahead.learning.content.exception.AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }
    UUID firstContact(CognitoIdentity identity){
        failsWith(()->service().admit(identity,proof()),403,"ACCOUNT_ADMISSION_REQUIRED");
        return jdbc.queryForObject("SELECT account_id FROM cloud_accounts WHERE issuer=? AND subject=?",UUID.class,identity.issuer(),identity.subject());
    }
    int events(UUID account,String action){return jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action=?",Integer.class,account,action);}
    AccountAdmissionService admission(){
        var owner=new com.lookahead.learning.content.security.LocalAuthorAccess(new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment","dev"));
        var catalog=mock(com.lookahead.learning.content.validator.SnapshotValidator.class);when(catalog.allTopicIds()).thenReturn(Set.of("java"));
        var staff=new StaffAccessRepository(jdbc,new tools.jackson.databind.ObjectMapper());
        return new AccountAdmissionService(jdbc,new StaffAccessService(staff,owner,catalog),staff,new tools.jackson.databind.ObjectMapper());
    }
    @Test void firstContactRecordsOneWaitingRowUntilTheOwnerAdmitsIt(){
        var a=contact("first-contact","first-contact@example.invalid");String p=proof();
        UUID id=firstContact(a);
        var row=jdbc.queryForMap("SELECT admitted,enabled,author_access,requested_at,admitted_at,admitted_by,verified_email,username,display_name FROM cloud_accounts WHERE account_id=?",id);
        assertThat(row).containsEntry("admitted",false).containsEntry("enabled",true).containsEntry("author_access",false)
            .containsEntry("verified_email","first-contact@example.invalid").containsEntry("username",a.username()).containsEntry("display_name",a.displayName());
        assertThat(row.get("requested_at")).isNotNull();assertThat(row.get("admitted_at")).isNull();assertThat(row.get("admitted_by")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_subjects WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(events(id,"request")).isEqualTo(1);
        // A later attempt before admission refreshes the claims, never duplicates the row and still answers 403.
        var renamed=new CognitoIdentity(issuer,a.subject(),UUID.randomUUID().toString(),"renamed@example.invalid","Renamed",Instant.now(),"renamed@example.invalid");
        failsWith(()->service().admit(renamed,p),403,"ACCOUNT_ADMISSION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE issuer=? AND subject=?",Integer.class,issuer,a.subject())).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT username,display_name,verified_email FROM cloud_accounts WHERE account_id=?",id)).containsEntry("username","renamed@example.invalid").containsEntry("display_name","Renamed").containsEntry("verified_email","renamed@example.invalid");
        assertThat(events(id,"request")).isEqualTo(1);
        // Waiting rows carry no session; other calls do not admit and never create rows.
        failsWith(()->registry.authenticate(renamed,p),401,"AUTHENTICATION_REQUIRED");
        var stranger=identity("never-contacted");
        failsWith(()->registry.authenticate(stranger,proof()),403,"ACCOUNT_ADMISSION_REQUIRED");
        failsWith(()->registry.inventory(stranger,proof()),403,"ACCOUNT_ADMISSION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE subject=?",Integer.class,stranger.subject())).isZero();
        // Disabled rows keep answering 401 even on the sign-in path.
        jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE account_id=?",id);
        failsWith(()->service().admit(renamed,p),401,"AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE cloud_accounts SET enabled=true,admitted=true WHERE account_id=?",id);
        assertThat(service().admit(renamed,p).signInId()).isNotNull();
        var principal=registry.authenticate(renamed,p);
        assertThat(principal.accountId()).isEqualTo(id);assertThat(principal.verifiedEmail()).isEqualTo("renamed@example.invalid");
    }
    @Test void concurrentFirstContactsCreateExactlyOneWaitingRow()throws Exception{
        var identities=List.of(contact("race-contact",null),contact("race-contact",null),contact("race-contact",null));
        try(var pool=Executors.newFixedThreadPool(3)){
            var latch=new CountDownLatch(1);var tasks=new ArrayList<Future<RuntimeException>>();
            for(var id:identities)tasks.add(pool.submit(()->{latch.await();try{service().admit(id,proof());return null;}catch(RuntimeException expected){return expected;}}));latch.countDown();
            for(var t:tasks)assertThat(t.get(10,TimeUnit.SECONDS)).isInstanceOfSatisfying(com.lookahead.learning.content.exception.AccountFailure.class,f->assertThat(f.code()).isEqualTo("ACCOUNT_ADMISSION_REQUIRED"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE issuer=? AND subject=?",Integer.class,issuer,"race-contact")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE subject=? AND action='request'",Integer.class,"race-contact")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT verified_email FROM cloud_accounts WHERE issuer=? AND subject=?",String.class,issuer,"race-contact")).isNull();
    }
    @Test void runtimeRoleRecordsFirstContactAndRunsTheCleanupWithTheV17Grants(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        var a=contact("runtime-contact","runtime@example.invalid");
        tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");failsWith(()->registry.admit(a,proof()),403,"ACCOUNT_ADMISSION_REQUIRED");return null;});
        UUID id=jdbc.queryForObject("SELECT account_id FROM cloud_accounts WHERE issuer=? AND subject=?",UUID.class,issuer,a.subject());
        assertThat(events(id,"request")).isEqualTo(1);
        jdbc.update("UPDATE cloud_accounts SET requested_at=clock_timestamp()-interval '49 hours' WHERE account_id=?",id);
        int removed=tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");return new CloudAdmissionCleanup(jdbc,new DataSourceTransactionManager(source)).expire();});
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id=?",Integer.class,id)).isZero();
        assertThat(events(id,"expire")).isEqualTo(1);
    }
    /** DLV-969: DEV readiness must accept the V17 grants (it once required the pre-V17 read-only shape and failed every task). */
    @Test void cloudReadinessAcceptsTheV17RuntimeGrantsAndRejectsDrift(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        // This suite builds the tables in its own schema; the readiness SQL names public, as the deployed database does.
        jdbc.execute("CREATE TABLE IF NOT EXISTS flyway_schema_history(version text, success boolean)");
        jdbc.execute("GRANT SELECT ON flyway_schema_history TO lookahead_platform_app");
        jdbc.update("INSERT INTO flyway_schema_history VALUES ('4',true),('17',true)");
        String sql=com.lookahead.domain.DomainDatabaseHealthIndicator.CLOUD_SQL.replace("public.",schema+".");
        java.util.function.Supplier<Boolean> asApp=()->tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");
            return jdbc.queryForObject(sql,Boolean.class);});
        assertThat(asApp.get()).isTrue();
        jdbc.execute("GRANT UPDATE(enabled) ON cloud_accounts TO lookahead_platform_app");
        try { assertThat(asApp.get()).isFalse(); } finally { jdbc.execute("REVOKE UPDATE(enabled) ON cloud_accounts FROM lookahead_platform_app"); }
        jdbc.execute("REVOKE INSERT ON cloud_accounts FROM lookahead_platform_app");
        try { assertThat(asApp.get()).isFalse(); } finally { jdbc.execute("GRANT INSERT ON cloud_accounts TO lookahead_platform_app"); }
        jdbc.execute("GRANT UPDATE ON cloud_admission_events TO lookahead_platform_app");
        try { assertThat(asApp.get()).isFalse(); } finally { jdbc.execute("REVOKE UPDATE ON cloud_admission_events FROM lookahead_platform_app"); }
        assertThat(asApp.get()).isTrue();
    }
    @Test void ownerAdmissionMarksTheRowAssignsRolesAndIsRecordedOnce(){
        var service=admission();UUID ownerId=provision(identity("admitting-owner"));
        var owner=new com.lookahead.learning.content.security.AccountPrincipal(ownerId,"owner","Owner",true,true);
        var learner=new com.lookahead.learning.content.security.AccountPrincipal(provision(identity("plain-learner")),"learner","Learner",true,false);
        UUID waiting=firstContact(contact("to-admit","to-admit@example.invalid"));
        var listed=service.waiting(owner);
        assertThat(listed.expiryHours()).isEqualTo(48);
        var entry=listed.waiting().stream().filter(w->w.accountId().equals(waiting)).findFirst().orElseThrow();
        assertThat(entry.email()).isEqualTo("to-admit@example.invalid");assertThat(entry.expiresAt()).isEqualTo(entry.requestedAt().plus(CloudAdmissionCleanup.WAITING_EXPIRY));
        failsWith(()->service.waiting(learner),403,"OWNER_REQUIRED");
        failsWith(()->service.admit(learner,new AccountAdmissionService.Request(waiting.toString(),List.of())),403,"OWNER_REQUIRED");
        failsWith(()->service.admit(owner,new AccountAdmissionService.Request(UUID.randomUUID().toString(),List.of())),404,"ACCOUNT_NOT_FOUND");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        var admitted=tx.execute(status->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of("tester","reviewer"))));
        assertThat(admitted.accountId()).isEqualTo(waiting);assertThat(admitted.roles()).containsExactly("reviewer","tester");assertThat(admitted.admittedAt()).isNotNull();
        var row=jdbc.queryForMap("SELECT admitted,admitted_at,admitted_by FROM cloud_accounts WHERE account_id=?",waiting);
        assertThat(row).containsEntry("admitted",true).containsEntry("admitted_by",ownerId);assertThat(row.get("admitted_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,waiting)).contains("reviewer").contains("tester");
        assertThat(jdbc.queryForObject("SELECT roles::text FROM cloud_admission_events WHERE account_id=? AND action='admit'",String.class,waiting)).isEqualTo("[\"reviewer\", \"tester\"]");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM cloud_admission_events WHERE account_id=? AND action='admit'",UUID.class,waiting)).isEqualTo(ownerId);
        assertThat(service.waiting(owner).waiting()).noneMatch(w->w.accountId().equals(waiting));
        failsWith(()->tx.execute(status->service.admit(owner,new AccountAdmissionService.Request(waiting.toString(),List.of()))),409,"ACCOUNT_ALREADY_ADMITTED");
        assertThat(events(waiting,"admit")).isEqualTo(1);
        // A rejected assignment rolls the admission back as one transaction.
        UUID second=firstContact(contact("to-admit-rollback",null));
        var conflicting=new AccountAdmissionService(jdbc,new StaffAccessService(new StaffAccessRepository(jdbc,new tools.jackson.databind.ObjectMapper()),
            new com.lookahead.learning.content.security.LocalAuthorAccess(new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment","dev")),
            mock(com.lookahead.learning.content.validator.SnapshotValidator.class)){
            @Override public StaffAccessRepository.Assignment save(com.lookahead.learning.content.security.AccountPrincipal actor,Change change){throw new com.lookahead.learning.content.exception.AccountFailure(409,"ACCESS_ASSIGNMENT_CONFLICT","synthetic");}
        },new StaffAccessRepository(jdbc,new tools.jackson.databind.ObjectMapper()),new tools.jackson.databind.ObjectMapper());
        failsWith(()->tx.execute(status->conflicting.admit(owner,new AccountAdmissionService.Request(second.toString(),List.of("admin")))),409,"ACCESS_ASSIGNMENT_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT admitted FROM cloud_accounts WHERE account_id=?",Boolean.class,second)).isFalse();
        assertThat(events(second,"admit")).isZero();
    }
    @Test void cleanupRemovesOnlyExpiredWaitingRowsAndAdmittedRowsAreNeverDeleted()throws Exception{
        UUID expired=firstContact(contact("expired-waiting","expired@example.invalid"));
        UUID fresh=firstContact(contact("fresh-waiting",null));
        UUID admitted=provision(identity("admitted-long-ago"));
        jdbc.update("UPDATE cloud_accounts SET requested_at=clock_timestamp()-interval '3 days' WHERE account_id IN (?,?)",expired,admitted);
        var cleanup=new CloudAdmissionCleanup(jdbc,new DataSourceTransactionManager(source));
        // While another connection holds the advisory lock the run does nothing.
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},CloudAdmissionCleanup.LOCK);held.countDown();
                try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(cleanup.expire()).isZero();
            release.countDown();holder.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id=?",Integer.class,expired)).isEqualTo(1);
        assertThat(cleanup.expire()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id=?",Integer.class,expired)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_subjects WHERE id=?",Integer.class,expired)).isZero();
        assertThat(events(expired,"expire")).isEqualTo(1);assertThat(events(expired,"request")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id IN (?,?)",Integer.class,fresh,admitted)).isEqualTo(2);
        assertThat(cleanup.expire()).isZero();
        assertThatThrownBy(()->jdbc.update("DELETE FROM cloud_accounts WHERE account_id=?",admitted)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("never deleted");
        assertThatThrownBy(()->jdbc.update("DELETE FROM cloud_admission_events WHERE account_id=?",expired)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE cloud_admission_events SET action='admit' WHERE account_id=?",expired)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id=?",Integer.class,admitted)).isEqualTo(1);
    }
    @Test void passwordChangeCommitsRevocationEvenWhenProviderSignOutFails(){
        var a=identity("password-owner");provision(a);String proof=proof();registry.admit(a,proof);
        var other=identity(a.subject());String otherProof=proof();registry.admit(other,otherProof);
        var fixture=new CognitoFixture();var changed=new java.util.concurrent.atomic.AtomicBoolean();
        fixture.changePassword=()->changed.set(true);fixture.globalSignOut=()->{throw new IllegalStateException("synthetic provider outage");};
        var controller=new CloudSignInController(registry,fixture.client);
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/internal/v1/cloud-sign-ins/password");
        request.addHeader("Authorization","Bearer synthetic");request.addHeader(CloudDomainTokenConverter.PROOF_HEADER,proof);
        assertThatThrownBy(()->controller.mutate(a,Map.of("currentPassword","old synthetic password","newPassword","new synthetic password","confirmPassword","new synthetic password"),request,new org.springframework.mock.web.MockHttpServletResponse()))
            .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class).hasMessageNotContaining("synthetic provider outage");
        assertThat(changed.get()).isTrue();
        assertThatThrownBy(()->service().authenticate(a,proof)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service().authenticate(other,otherProof)).isInstanceOf(RuntimeException.class);
    }
    @Test void ambiguousPasswordChangeRevokesOldSessionsAndPendingChallenges(){
        var a=identity("ambiguous-password");provision(a);String proof=proof();var first=registry.admit(a,proof);
        var b=identity(a.subject());String bp=proof();registry.admit(b,bp);
        var c=identity(a.subject());String cp=proof();var challenge=registry.admit(c,cp);
        var fixture=new CognitoFixture();fixture.changePassword=()->{throw new IllegalStateException("provider changed password but response was lost");};
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/internal/v1/cloud-sign-ins/password");request.addHeader("Authorization","Bearer synthetic");request.addHeader(CloudDomainTokenConverter.PROOF_HEADER,proof);
        assertThatThrownBy(()->new CloudSignInController(registry,fixture.client).mutate(a,Map.of("currentPassword","old synthetic password","newPassword","new synthetic password","confirmPassword","new synthetic password"),request,new org.springframework.mock.web.MockHttpServletResponse())).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        assertThatThrownBy(()->service().authenticate(a,proof)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service().authenticate(b,bp)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.replace(c,cp,challenge.challengeToken(),first.signInId())).isInstanceOf(RuntimeException.class);
    }
    @Test void knownPasswordRejectionPreservesSessionsAfterTheDurableGuardIsCleared(){
        var a=identity("rejected-password");provision(a);String proof=proof();registry.admit(a,proof);
        assertThatThrownBy(()->registry.changeCredentials(a,proof,()->{
            assertThatThrownBy(()->service().authenticate(a,proof)).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
            assertThatThrownBy(()->service().admit(identity(a.subject()),proof())).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
            throw new com.lookahead.learning.content.exception.AccountFailure(401,"INVALID_CURRENT_PASSWORD","Current password could not be verified.");
        })).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        assertThat(service().authenticate(a,proof)).isNotNull();
    }
    @Test void failedPostProviderCommitLeavesPersistentGuardUntilDisabledOperatorRecovery(){
        var a=identity("commit-failure");UUID owner=provision(a);String proof=proof();registry.admit(a,proof);
        jdbc.execute("CREATE FUNCTION reject_revocation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic commit failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_revocation BEFORE UPDATE ON cloud_sign_ins FOR EACH ROW EXECUTE FUNCTION reject_revocation()");
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        try {assertThatThrownBy(()->registry.changeCredentials(a,proof,()->changed.set(true))).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally {jdbc.execute("DROP TRIGGER reject_revocation ON cloud_sign_ins");jdbc.execute("DROP FUNCTION reject_revocation()");}
        assertThat(changed.get()).isTrue();
        assertThat(jdbc.queryForObject("SELECT credential_change_pending FROM cloud_accounts WHERE account_id=?",Boolean.class,owner)).isTrue();
        assertThatThrownBy(()->service().authenticate(a,proof)).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        assertThatThrownBy(()->CloudAccountAdministration.apply(jdbc,tx,new String[]{"resolve-password-change",issuer,a.subject()})).isInstanceOf(IllegalArgumentException.class);
        CloudAccountAdministration.apply(jdbc,tx,new String[]{"disable",issuer,a.subject()});
        CloudAccountAdministration.apply(jdbc,tx,new String[]{"resolve-password-change",issuer,a.subject()});
        CloudAccountAdministration.apply(jdbc,tx,new String[]{"enable",issuer,a.subject()});
        assertThatThrownBy(()->service().authenticate(a,proof)).isInstanceOf(RuntimeException.class);
        var fresh=identity(a.subject());registry.admit(fresh,proof);assertThat(service().authenticate(fresh,proof)).isNotNull();
    }
}
