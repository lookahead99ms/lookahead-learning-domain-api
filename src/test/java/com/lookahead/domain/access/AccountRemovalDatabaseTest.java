package com.lookahead.domain.access;

import com.lookahead.domain.cloud.CognitoSignInAccounts;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 V18 against the disposable cloud-auth database: the full Domain schema (V1-V18) in a fresh schema, the
 * sign-in pool mocked. Disable, Enable and Delete; the permanent role labels; the V18 grants and triggers.
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountRemovalDatabaseTest {
    static final String ISSUER="https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
    JdbcTemplate jdbc; DriverManagerDataSource source; String schema; final ObjectMapper json=new ObjectMapper();
    StaffAccessService staff; StaffAccessRepository repository; CognitoSignInAccounts pool;
    AccountPrincipal owner; UUID ownerId;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        schema="removal_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
        source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO lookahead_platform_app");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
        for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql",
                "V7__cloud_account_preferences.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql","V11__free_plan_allowances.sql",
                "V12__created_test_accounts.sql","V13__real_account_maintenance.sql","V14__managed_test_purchase_purge_grants.sql","V15__retain_runtime_audit.sql",
                "V17__dev_access_admission.sql")) {
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
            // Two people with roles before V18, to prove its numbering of today's staff.
            if(file.startsWith("V17"))seedStaffBeforeV18();
        }
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V18__access_removal_and_staff_aliases.sql")));
        repository=new StaffAccessRepository(jdbc,json);
        staff=new StaffAccessService(repository,new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment","dev")),mock(SnapshotValidator.class));
        ownerId=account("owner-subject","owner@example.invalid",true,true);
        owner=new AccountPrincipal(ownerId,"owner@example.invalid","Owner",true,true);
    }
    @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @BeforeEach void freshPool(){pool=mock(CognitoSignInAccounts.class);}

    UUID earlyCoauthor, earlyAdmin, laterCoauthor;
    void seedStaffBeforeV18(){
        earlyCoauthor=account("early-coauthor","early@example.invalid",true,false);
        laterCoauthor=account("later-coauthor","later@example.invalid",true,false);
        earlyAdmin=account("early-admin","admin@example.invalid",true,false);
        jdbc.update("INSERT INTO staff_access(id,account_id,roles,updated_at) VALUES (?,?,'[\"co-author\",\"admin\"]','2026-10-01'),(?,?,'[\"co-author\"]','2026-10-02'),(?,?,'[\"admin\"]','2026-10-03')",
            UUID.randomUUID(),earlyCoauthor,UUID.randomUUID(),laterCoauthor,UUID.randomUUID(),earlyAdmin);
    }
    UUID account(String subject,String username,boolean admitted,boolean author){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,author_access,username,display_name,verified_email) VALUES (?,?,?,?,?,?,?,?)",
            id,ISSUER,subject,admitted,author,username,"Name of "+subject,username);
        return id;
    }
    AccountRemovalService service(CognitoSignInAccounts signIns){
        var beans=new StaticListableBeanFactory();if(signIns!=null)beans.addBean("cognitoSignInAccounts",signIns);
        ObjectProvider<CognitoSignInAccounts> provider=beans.getBeanProvider(CognitoSignInAccounts.class);
        return new AccountRemovalService(jdbc,staff,provider,new DataSourceTransactionManager(source));
    }
    int events(UUID id,String action){return jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action=?",Integer.class,id,action);}
    String state(UUID id){return jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?",String.class,id).stream().findFirst().orElse(null);}
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status,String code){
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }

    @Test void v18NumbersTodaysStaffAndKeepsLabelsAndDeletedAccountsPermanent(){
        assertThat(jdbc.queryForList("SELECT role||' '||number FROM staff_aliases WHERE account_id=? ORDER BY role",String.class,earlyCoauthor)).containsExactly("admin 1","co-author 1");
        assertThat(jdbc.queryForList("SELECT role||' '||number FROM staff_aliases WHERE account_id=?",String.class,laterCoauthor)).containsExactly("co-author 2");
        assertThat(jdbc.queryForList("SELECT role||' '||number FROM staff_aliases WHERE account_id=?",String.class,earlyAdmin)).containsExactly("admin 2");
        assertThat(refused(()->jdbc.update("UPDATE staff_aliases SET number=9 WHERE account_id=?",laterCoauthor))).isTrue();
        assertThat(refused(()->jdbc.update("DELETE FROM staff_aliases WHERE account_id=?",laterCoauthor))).isTrue();
    }
    /** True when the database refuses {@code call} (a trigger or a constraint). */
    static boolean refused(Runnable call){
        try{call.run();return false;}catch(org.springframework.dao.DataAccessException refused){return true;}
    }
    @Test void v18GrantsTheRuntimeOnlyWhatItNeedsAndTheEventsAcceptTheNewActions(){
        String sql="""
            SELECT has_table_privilege('lookahead_platform_app','%1$s.cloud_account_states','SELECT,INSERT,UPDATE,DELETE')
              AND NOT has_table_privilege('lookahead_platform_app','%1$s.cloud_account_states','TRUNCATE')
              AND has_table_privilege('lookahead_platform_app','%1$s.staff_aliases','SELECT')
              AND has_table_privilege('lookahead_platform_app','%1$s.staff_aliases','INSERT')
              AND NOT has_table_privilege('lookahead_platform_app','%1$s.staff_aliases','UPDATE')
              AND NOT has_table_privilege('lookahead_platform_app','%1$s.staff_aliases','DELETE')
              AND NOT has_column_privilege('lookahead_platform_app','%1$s.cloud_accounts','enabled','UPDATE')
            """.formatted(schema);
        assertThat(jdbc.queryForObject(sql,Boolean.class)).isTrue();
        UUID id=account("events-subject","events@example.invalid",true,false);
        for(String action:List.of("disable","enable","remove"))
            jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,action) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,ISSUER,"events-subject",action);
        assertThat(refused(()->jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,action) VALUES (?,?,?,?,'other')",UUID.randomUUID(),id,ISSUER,"x"))).isTrue();
        // A removed account stays removed: no update back to disabled, no delete.
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)",id,ownerId);
        assertThat(refused(()->jdbc.update("UPDATE cloud_account_states SET state='disabled' WHERE account_id=?",id))).isTrue();
        assertThat(refused(()->jdbc.update("DELETE FROM cloud_account_states WHERE account_id=?",id))).isTrue();
    }

    @Test void disableBlocksThenEnableRestoresAndEachIsRecordedOnce(){
        UUID id=account("to-disable","learner@gmail.invalid",true,false);
        jdbc.update("INSERT INTO cloud_sign_ins(id,account_id,family_digest,binding_digest,created_at,last_active_at,recent_auth_at) VALUES (?,?,?,?,now(),now(),now())",
            UUID.randomUUID(),id,"f".repeat(64),"b".repeat(64));
        var service=service(pool);
        assertThat(service.options(ownerId,id,false)).isEqualTo(new AccountRemovalService.Options("active",true,false,true));
        failsWith(()->service.disable(owner,id,new AccountRemovalService.Confirmation("DELETE")),422,"ACCOUNT_CONFIRMATION_REQUIRED");
        assertThat(service.disable(owner,id,new AccountRemovalService.Confirmation("DISABLE")).state()).isEqualTo("disabled");
        assertThat(state(id)).isEqualTo("disabled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_sign_ins WHERE account_id=? AND revoked_at IS NULL",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT actor_id FROM cloud_admission_events WHERE account_id=? AND action='disable'",UUID.class,id)).isEqualTo(ownerId);
        verify(pool).disable("learner@gmail.invalid");
        assertThat(service.options(ownerId,id,false)).isEqualTo(new AccountRemovalService.Options("disabled",false,true,true));
        // A retry repeats only the pool call.
        service.disable(owner,id,new AccountRemovalService.Confirmation("DISABLE"));
        verify(pool,times(2)).disable("learner@gmail.invalid");assertThat(events(id,"disable")).isEqualTo(1);
        assertThat(service.enable(owner,id).state()).isEqualTo("active");
        assertThat(state(id)).isNull();assertThat(events(id,"enable")).isEqualTo(1);verify(pool).enable("learner@gmail.invalid");
        service.enable(owner,id);assertThat(events(id,"enable")).isEqualTo(1);verify(pool,times(2)).enable("learner@gmail.invalid");
    }

    @Test void deleteErasesLearningDataKeepsPurchaseHistoryAndIsFinal(){
        UUID id=account("to-delete","gone@gmail.invalid",true,false);
        jdbc.update("INSERT INTO staff_access(id,account_id,roles,full_curriculum) VALUES (?,?,'[\"tester\"]',true)",UUID.randomUUID(),id);
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:java-foundations')",id);
        jdbc.update("INSERT INTO cloud_account_preferences(account_id,theme) VALUES (?,'dark')",id);
        jdbc.update("INSERT INTO cloud_account_profile_links(account_id,kind,url) VALUES (?,'github','https://github.com/example')",id);
        jdbc.update("INSERT INTO support_receipts(account_id,request_key,request_hash,reference,status) VALUES (?,'k',?,?,'accepted')",id,"h".repeat(64),UUID.randomUUID());
        var service=service(pool);
        service.disable(owner,id,new AccountRemovalService.Confirmation("DISABLE"));
        failsWith(()->service.remove(owner,id,new AccountRemovalService.Confirmation("DISABLE")),422,"ACCOUNT_CONFIRMATION_REQUIRED");
        assertThat(service.remove(owner,id,new AccountRemovalService.Confirmation("DELETE")).state()).isEqualTo("removed");
        assertThat(state(id)).isEqualTo("removed");verify(pool).remove("gone@gmail.invalid");
        assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,id)).isEqualTo("[]");
        for(String table:List.of("account_grants","cloud_account_preferences","cloud_account_profile_links","support_receipts","plans"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Integer.class,id)).as(table).isZero();
        assertThat(service.options(ownerId,id,false)).isEqualTo(AccountRemovalService.Options.NONE);
        // Repeating Delete only repeats the pool call; Disable and Enable are refused.
        service.remove(owner,id,new AccountRemovalService.Confirmation("DELETE"));
        assertThat(events(id,"remove")).isEqualTo(1);verify(pool,times(2)).remove("gone@gmail.invalid");
        failsWith(()->service.disable(owner,id,new AccountRemovalService.Confirmation("DISABLE")),409,"ACCOUNT_REMOVED");
        failsWith(()->service.enable(owner,id),409,"ACCOUNT_REMOVED");
        // The deleted row stays (admitted), so the 48-hour cleanup and the waiting list never see it.
        assertThat(jdbc.queryForObject("SELECT admitted FROM cloud_accounts WHERE account_id=?",Boolean.class,id)).isTrue();
    }

    @Test void onlyTheOwnerActsAndNeverOnThemselvesAnAuthorATestAccountOrAWaitingRow(){
        var service=service(pool);
        UUID learner=account("plain","plain@example.invalid",true,false);
        var coauthor=new AccountPrincipal(earlyCoauthor,"early@example.invalid","Early",true,false);
        failsWith(()->service.disable(coauthor,learner,new AccountRemovalService.Confirmation("DISABLE")),403,"OWNER_REQUIRED");
        failsWith(()->service.enable(coauthor,learner),403,"OWNER_REQUIRED");
        failsWith(()->service.remove(coauthor,learner,new AccountRemovalService.Confirmation("DELETE")),403,"OWNER_REQUIRED");
        failsWith(()->service.disable(owner,ownerId,new AccountRemovalService.Confirmation("DISABLE")),403,"OWNER_ACCOUNT_PROTECTED");
        UUID author=account("second-author","author2@example.invalid",true,true);
        failsWith(()->service.disable(owner,author,new AccountRemovalService.Confirmation("DISABLE")),403,"OWNER_ACCOUNT_PROTECTED");
        UUID test=account("test-subject","test-learner-01",true,false);
        jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,?,'created','seed')",test,"test-learner-01");
        failsWith(()->service.remove(owner,test,new AccountRemovalService.Confirmation("DELETE")),403,"TEST_ACCOUNT_ROUTE_REQUIRED");
        UUID waiting=account("waiting-subject","waiting@example.invalid",false,false);
        failsWith(()->service.disable(owner,waiting,new AccountRemovalService.Confirmation("DISABLE")),404,"SIGN_IN_ACCOUNT_NOT_FOUND");
        failsWith(()->service.disable(owner,UUID.randomUUID(),new AccountRemovalService.Confirmation("DISABLE")),404,"SIGN_IN_ACCOUNT_NOT_FOUND");
        for(UUID id:List.of(author,test,waiting,ownerId))
            assertThat(service.options(ownerId,id,id.equals(test))).isEqualTo(AccountRemovalService.Options.NONE);
        // Local delivery has no sign-in pool: no options and a clear refusal.
        var local=service(null);
        assertThat(local.options(ownerId,learner,false)).isEqualTo(AccountRemovalService.Options.NONE);
        failsWith(()->local.enable(owner,learner),404,"SIGN_IN_ACCOUNT_NOT_FOUND");
        verifyNoInteractions(pool);
        assertThat(state(learner)).isNull();
    }

    @Test void aPoolFailureKeepsTheSavedStateAndARetryFinishesIt(){
        UUID id=account("pool-down","down@example.invalid",true,false);
        doThrow(new AccountFailure(503,"SIGN_IN_PROVIDER_UNAVAILABLE","retry")).doNothing().when(pool).disable("down@example.invalid");
        var service=service(pool);
        failsWith(()->service.disable(owner,id,new AccountRemovalService.Confirmation("DISABLE")),503,"SIGN_IN_PROVIDER_UNAVAILABLE");
        assertThat(state(id)).isEqualTo("disabled");
        service.disable(owner,id,new AccountRemovalService.Confirmation("DISABLE"));
        verify(pool,times(2)).disable("down@example.invalid");assertThat(events(id,"disable")).isEqualTo(1);
    }

    @Test void theDirectoryListsDisabledAccountsWithTheirGmailButNeverDeletedOnes(){
        var directory=new AccountDirectory(new StaticListableBeanFactory().getBeanProvider(com.lookahead.domain.security.IdentitySettings.class),jdbc);
        UUID shown=account("listed-disabled","listed@gmail.invalid",true,false),hidden=account("listed-removed","hidden@gmail.invalid",true,false);
        var service=service(pool);
        service.disable(owner,shown,new AccountRemovalService.Confirmation("DISABLE"));
        service.remove(owner,hidden,new AccountRemovalService.Confirmation("DELETE"));
        var listed=directory.list("gmail.invalid",0).accounts();
        assertThat(listed).extracting(AccountDirectory.Account::accountId).contains(shown).doesNotContain(hidden);
        assertThat(listed.stream().filter(a->a.accountId().equals(shown)).findFirst().orElseThrow().email()).isEqualTo("listed@gmail.invalid");
        failsWith(()->directory.lookup("hidden@gmail.invalid"),404,"ACCOUNT_NOT_FOUND");
        assertThat(directory.lookup("listed@gmail.invalid").accountId()).isEqualTo(shown);
    }

    @Test void labelsArePermanentPerRoleAndTheOwnerIsOnlyTheOwner(){
        var aliases=new StaffAliases(jdbc,staff,repository,new DataSourceTransactionManager(source));
        assertThat(aliases.label(ownerId)).isEqualTo("Owner");
        assertThat(aliases.label(LocalAuthorAccess.ACCOUNT_ID)).isEqualTo("Owner");
        assertThat(aliases.label(null)).isEqualTo("Unknown");
        assertThat(aliases.label(earlyCoauthor)).isEqualTo("Co-author 1");
        assertThat(aliases.label(laterCoauthor)).isEqualTo("Co-author 2");
        assertThat(aliases.label(earlyAdmin)).isEqualTo("Admin 2");
        UUID learner=account("label-learner","label@example.invalid",true,false),second=account("label-learner-2","label2@example.invalid",true,false);
        assertThat(aliases.label(learner)).isEqualTo("Learner 1");
        assertThat(aliases.label(second)).isEqualTo("Learner 2");
        assertThat(aliases.label(learner)).isEqualTo("Learner 1");
        // A new role gives a new label and number; the old number is kept, never reused.
        jdbc.update("INSERT INTO staff_access(id,account_id,roles) VALUES (?,?,'[\"reviewer\"]')",UUID.randomUUID(),learner);
        assertThat(aliases.label(learner)).isEqualTo("Reviewer 1");
        assertThat(aliases.label(account("label-learner-3","label3@example.invalid",true,false))).isEqualTo("Learner 3");
        // Read-only callers (the test-account list) can still be given a number: it is written in its own transaction.
        var readOnly=new TransactionTemplate(new DataSourceTransactionManager(source));readOnly.setReadOnly(true);
        UUID late=account("label-late","late@example.invalid",true,false);
        assertThat(readOnly.<String>execute(status->aliases.label(late))).isEqualTo("Learner 4");
        assertThat(aliases.seesIdentities(owner)).isTrue();
        assertThat(aliases.seesIdentities(new AccountPrincipal(earlyCoauthor,"early@example.invalid","Early",true,false))).isFalse();
        assertThat(aliases.seesIdentities(null)).isFalse();
    }
}
