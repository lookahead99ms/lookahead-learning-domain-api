package com.lookahead.domain.cloud;

import com.lookahead.domain.access.AccountAdmissionService;
import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.testaccounts.TestAccountRoster;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 S36 (owner decision 2026-11-04) on the disposable cloud-auth database: one Gmail, one account. A new sign-in
 * whose provider-verified email matches exactly one eligible account joins it (V19 cloud_identities); anything else is
 * a new waiting account, never a guess.
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudIdentityLinkingDatabaseTest {
    static final String ISSUER="https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
    static final List<String> BEFORE_V19=List.of("V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql","V7__cloud_account_preferences.sql",
        "V17__dev_access_admission.sql","V18__access_removal_and_staff_aliases.sql");
    String url,password;JdbcTemplate jdbc;DriverManagerDataSource source;final List<String> schemas=new ArrayList<>();
    UUID ownerId;AccountPrincipal owner;

    @BeforeAll void start()throws Exception {
        url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
        password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        source=schema("linking_");jdbc=new JdbcTemplate(source);
        jdbc.execute(sql("V19__cloud_identities.sql"));jdbc.execute(sql("V20__cloud_merge_tokens.sql"));
        ownerId=admitted(identity("owner-subject","owner@example.invalid","owner@example.invalid"));
        jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE account_id=?",ownerId);
        owner=new AccountPrincipal(ownerId,"owner@example.invalid","Owner",true,true);
    }
    @AfterAll void stop(){var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));for(String s:schemas)admin.execute("DROP SCHEMA "+s+" CASCADE");}

    /** A fresh schema with the cloud sign-in migrations before V19 applied. */
    DriverManagerDataSource schema(String prefix)throws Exception {
        String name=prefix+UUID.randomUUID().toString().replace("-","");schemas.add(name);
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));admin.execute("CREATE SCHEMA "+name);
        if(admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='lookahead_platform_app'",Integer.class)==0)admin.execute("CREATE ROLE lookahead_platform_app NOLOGIN");
        var data=new DriverManagerDataSource(url+"?currentSchema="+name,"postgres",password);var target=new JdbcTemplate(data);
        target.execute("GRANT USAGE ON SCHEMA "+name+" TO lookahead_platform_app");
        target.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");target.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON platform_subjects TO lookahead_platform_app");
        target.execute("CREATE FUNCTION reject_author_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'append-only'; END; $$");
        for(String file:BEFORE_V19)target.execute(sql(file));
        return data;
    }
    static String sql(String file)throws Exception{return Files.readString(Path.of("src/main/resources/db/domain",file));}
    CloudSignInRegistry registry(){return new CloudSignInRegistry(jdbc,new DataSourceTransactionManager(source));}
    static CognitoIdentity identity(String subject,String username,String email){return new CognitoIdentity(ISSUER,subject,UUID.randomUUID().toString(),username,"Name of "+subject,Instant.now(),email);}
    static CognitoIdentity google(String subject,String email){return identity(subject,"Google_"+subject.hashCode(),email);}
    static CognitoIdentity password(String subject,String email){return identity(subject,email,email);}
    static String proof(){return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status,String code){
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }
    /** First contact (403, a waiting row or a link) and the account it reached. */
    UUID contact(CognitoIdentity identity){
        failsWith(()->registry().admit(identity,proof()),403,"ACCOUNT_ADMISSION_REQUIRED");
        return accountOf(identity);
    }
    UUID admitted(CognitoIdentity identity){UUID id=contact(identity);jdbc.update("UPDATE cloud_accounts SET admitted=true,admitted_at=clock_timestamp() WHERE account_id=?",id);return id;}
    UUID accountOf(CognitoIdentity identity){return jdbc.queryForObject("SELECT account_id FROM cloud_identities WHERE issuer=? AND subject=?",UUID.class,identity.issuer(),identity.subject());}
    int identities(UUID account){return jdbc.queryForObject("SELECT count(*) FROM cloud_identities WHERE account_id=?",Integer.class,account);}
    int events(UUID account,String action){return jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action=?",Integer.class,account,action);}
    int accountsWith(String email){return jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE verified_email=?",Integer.class,email);}
    AccountAdmissionService admission(){
        var local=new com.lookahead.learning.content.security.LocalAuthorAccess(new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment","dev"));
        var staff=new StaffAccessRepository(jdbc,new tools.jackson.databind.ObjectMapper());
        return new AccountAdmissionService(jdbc,new StaffAccessService(staff,local,mock(com.lookahead.learning.content.validator.SnapshotValidator.class)),staff,new tools.jackson.databind.ObjectMapper());
    }
    AccountAdmissionService.Waiting waiting(UUID account){return admission().waiting(owner).waiting().stream().filter(w->w.accountId().equals(account)).findFirst().orElseThrow();}

    @Test void googleThenPasswordWithTheSameGmailSignInToTheSameAccount(){
        var g=google("gp-google","gp@gmail.invalid");UUID account=admitted(g);
        var p=password("gp-password","gp@gmail.invalid");String pp=proof();
        CloudSignInRegistry.Admission signIn;
        try(var log=com.lookahead.domain.LogCapture.start()){
            signIn=registry().admit(p,pp);
            // DLV-969 log: the link is one INFO line after the commit, with the account and provider, never the email or username.
            var linked=log.only("identity.linked");
            assertThat(linked.getFormattedMessage()).isEqualTo("New cognito sign-in linked to account "+account+": its verified email matched exactly one eligible account");
            assertThat(com.lookahead.domain.LogCapture.keys(linked)).containsEntry("event.outcome","success").containsEntry("account.id",account).containsEntry("provider","cognito").containsEntry("admitted",true);
            assertThat(log.lines("identity.link-skipped")).isEmpty();assertThat(log.lines("admission.waiting-created")).isEmpty();
            log.containsNone("gp@gmail.invalid","gp-password");
        }
        assertThat(signIn.accountId()).isEqualTo(account);assertThat(signIn.signInId()).isNotNull();
        assertThat(registry().authenticate(p,pp).accountId()).isEqualTo(account);
        assertThat(identities(account)).isEqualTo(2);assertThat(accountsWith("gp@gmail.invalid")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT username,provider,verified_email,link_source FROM cloud_identities WHERE subject=?","gp-password"))
            .containsEntry("username","gp@gmail.invalid").containsEntry("provider","cognito").containsEntry("verified_email","gp@gmail.invalid").containsEntry("link_source","linked");
        assertThat(jdbc.queryForMap("SELECT provider,link_source FROM cloud_identities WHERE subject=?","gp-google")).containsEntry("provider","google").containsEntry("link_source","first_contact");
        assertThat(events(account,"link")).isEqualTo(1);assertThat(events(account,"request")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject FROM cloud_admission_events WHERE account_id=? AND action='link'",String.class,account)).isEqualTo("gp-password");
        // The page lists both methods; signInMethod is the one making the call. The account keeps its first identity.
        var view=registry().profile(p,pp,null);
        assertThat(view.get("signInMethod")).isEqualTo("cognito");assertThat(view.get("email")).isEqualTo("gp@gmail.invalid");
        assertThat(view.get("signInMethods")).isEqualTo(List.of(Map.of("provider","google","email","gp@gmail.invalid"),Map.of("provider","cognito","email","gp@gmail.invalid")));
        String gp=proof();registry().admit(g,gp);
        assertThat(registry().profile(g,gp,null).get("signInMethod")).isEqualTo("google");
        assertThat(jdbc.queryForMap("SELECT issuer,subject,username FROM cloud_accounts WHERE account_id=?",account))
            .containsEntry("issuer",ISSUER).containsEntry("subject","gp-google").containsEntry("username",g.username());
    }
    @Test void passwordThenGoogleJoinsTheWaitingAccountAndTheSameIdentityTwiceIsOneRow(){
        var p=password("pg-password","pg@gmail.invalid");UUID account=contact(p);
        var g=google("pg-google","pg@gmail.invalid");
        assertThat(contact(g)).isEqualTo(account);
        // The same identity again is a known identity: no second row, no second link, still waiting.
        var again=new CognitoIdentity(ISSUER,"pg-google",UUID.randomUUID().toString(),"Google_renamed","Renamed",Instant.now(),"pg@gmail.invalid");
        assertThat(contact(again)).isEqualTo(account);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_identities WHERE subject='pg-google'",Integer.class)).isEqualTo(1);
        assertThat(identities(account)).isEqualTo(2);assertThat(events(account,"link")).isEqualTo(1);assertThat(accountsWith("pg@gmail.invalid")).isEqualTo(1);
        // A linked identity refreshes only its own claims; the account's profile follows its first identity.
        assertThat(jdbc.queryForObject("SELECT username FROM cloud_identities WHERE subject='pg-google'",String.class)).isEqualTo("Google_renamed");
        assertThat(jdbc.queryForMap("SELECT username,display_name FROM cloud_accounts WHERE account_id=?",account)).containsEntry("username","pg@gmail.invalid").containsEntry("display_name","Name of pg-password");
        // The owner sees one waiting person, not flagged; admitting it admits both sign-ins.
        assertThat(waiting(account).sameEmailAccounts()).isZero();
        assertThat(admission().waiting(owner).waiting()).filteredOn(w->"pg@gmail.invalid".equals(w.email())).hasSize(1);
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->admission().admit(owner,new AccountAdmissionService.Request(account.toString(),List.of())));
        assertThat(registry().admit(g,proof()).accountId()).isEqualTo(account);assertThat(registry().admit(p,proof()).accountId()).isEqualTo(account);
        // Unique (issuer, subject): an identity is linked to one account only.
        assertThatThrownBy(()->jdbc.update("INSERT INTO cloud_identities(id,account_id,issuer,subject,username,provider,link_source) VALUES (?,?,?,?,'x','google','linked')",UUID.randomUUID(),ownerId,ISSUER,"pg-google"))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    @Test void twoFirstContactsForOneGmailAtTheSameMomentMakeOneAccount()throws Exception {
        for(int round=0;round<5;round++){
            String email="race"+round+"@gmail.invalid";var pair=List.of(google("race-google-"+round,email),password("race-password-"+round,email));
            try(var pool=Executors.newFixedThreadPool(2)){
                var latch=new CountDownLatch(1);var tasks=new ArrayList<Future<RuntimeException>>();
                for(var identity:pair)tasks.add(pool.submit(()->{latch.await();try{registry().admit(identity,proof());return null;}catch(RuntimeException expected){return expected;}}));
                latch.countDown();
                for(var task:tasks)assertThat(task.get(10,TimeUnit.SECONDS)).isInstanceOfSatisfying(AccountFailure.class,f->assertThat(f.code()).isEqualTo("ACCOUNT_ADMISSION_REQUIRED"));
            }
            assertThat(accountsWith(email)).as(email).isEqualTo(1);
            UUID account=accountOf(pair.get(0));
            assertThat(accountOf(pair.get(1))).isEqualTo(account);assertThat(identities(account)).isEqualTo(2);
            assertThat(events(account,"request")).isEqualTo(1);assertThat(events(account,"link")).isEqualTo(1);
        }
    }
    @Test void anUnverifiedEmailNeverLinks(){
        UUID account=admitted(google("unverified-google","unverified@gmail.invalid"));
        var unverified=identity("unverified-password","unverified@gmail.invalid",null);
        UUID other=contact(unverified);
        assertThat(other).isNotEqualTo(account);assertThat(identities(account)).isEqualTo(1);assertThat(events(account,"link")).isZero();
        assertThat(jdbc.queryForObject("SELECT verified_email FROM cloud_identities WHERE subject='unverified-password'",String.class)).isNull();
    }
    @Test void twoAccountsWithTheGmailMeanNoLinkAndTheOwnerSeesTheCount(){
        UUID first=admitted(google("twice-google","twice@gmail.invalid"));
        UUID second=admitted(identity("twice-unverified","twice@gmail.invalid",null));
        // The second account's email is verified later (a code entered after first contact).
        jdbc.update("UPDATE cloud_identities SET verified_email='twice@gmail.invalid' WHERE subject='twice-unverified'");
        UUID third;
        try(var log=com.lookahead.domain.LogCapture.start()){
            third=contact(password("twice-password","twice@gmail.invalid"));
            // DLV-969 log: why no link happened (the duplicate situation the merge resolves), then the usual waiting line.
            var skipped=log.only("identity.link-skipped");
            assertThat(skipped.getFormattedMessage()).isEqualTo("New cognito sign-in not linked: 2 eligible accounts already share its verified email; recorded as a new waiting account");
            assertThat(com.lookahead.domain.LogCapture.keys(skipped)).containsEntry("reason","SEVERAL_SAME_EMAIL_ACCOUNTS").containsEntry("count",2).containsEntry("provider","cognito");
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("admission.waiting-created"))).containsEntry("account.id",third);
            assertThat(log.lines("identity.linked")).isEmpty();
            log.containsNone("twice@gmail.invalid","twice-password");
        }
        assertThat(third).isNotIn(first,second);
        assertThat(identities(first)).isEqualTo(1);assertThat(identities(second)).isEqualTo(1);assertThat(events(first,"link")+events(second,"link")).isZero();
        assertThat(waiting(third).sameEmailAccounts()).isEqualTo(2);
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,first,"twice@gmail.invalid")).isEqualTo(2);
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,first,"TWICE@gmail.invalid")).isEqualTo(2);
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,first,null)).isZero();
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,first,"nobody@gmail.invalid")).isZero();
    }
    @Test void aDeletedAccountIsNeverACandidateOrCounted(){
        UUID removed=admitted(google("removed-google","removed@gmail.invalid"));
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)",removed,ownerId);
        UUID fresh=contact(google("removed-google-again","removed@gmail.invalid"));
        assertThat(fresh).isNotEqualTo(removed);assertThat(identities(removed)).isEqualTo(1);
        assertThat(waiting(fresh).sameEmailAccounts()).isZero();
    }
    @Test void aDisabledAccountIsNeverACandidateButIsCounted(){
        UUID disabled=admitted(google("disabled-google","disabled@gmail.invalid"));
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",disabled,ownerId);
        UUID fresh=contact(password("disabled-password","disabled@gmail.invalid"));
        assertThat(fresh).isNotEqualTo(disabled);assertThat(identities(disabled)).isEqualTo(1);
        assertThat(waiting(fresh).sameEmailAccounts()).isEqualTo(1);
        // The operator's enabled=false (CloudAccountAdministration) excludes an account too.
        UUID operatorDisabled=admitted(google("operator-disabled","operator-off@gmail.invalid"));
        jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE account_id=?",operatorDisabled);
        assertThat(contact(password("operator-disabled-password","operator-off@gmail.invalid"))).isNotEqualTo(operatorDisabled);
        assertThat(identities(operatorDisabled)).isEqualTo(1);
    }
    @Test void aTestAccountIsNeverACandidateOrCounted(){
        UUID test=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",test);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,?,true,'learner01@lookahead.dev','Learner01','tested@gmail.invalid')",
            test,TestAccountRoster.TEST_ISSUER,test.toString());
        CloudIdentities.insert(jdbc,test,TestAccountRoster.TEST_ISSUER,test.toString(),"learner01@lookahead.dev","tested@gmail.invalid","first_contact");
        assertThat(jdbc.queryForObject("SELECT provider FROM cloud_identities WHERE account_id=?",String.class,test)).isEqualTo("test");
        UUID real=contact(google("tested-google","tested@gmail.invalid"));
        assertThat(real).isNotEqualTo(test);assertThat(identities(test)).isEqualTo(1);
        assertThat(waiting(real).sameEmailAccounts()).isZero();
    }
    @Test void aRowFromAReleaseBeforeV19StillSignsInGetsItsIdentityAndCountsAsACandidate(){
        // The release running while V19 is applied records first contacts in cloud_accounts only.
        var legacy=password("legacy-password","legacy@gmail.invalid");UUID account=UUID.randomUUID();
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",account);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,?,true,?,?,?)",account,ISSUER,legacy.subject(),legacy.username(),"Legacy","legacy@gmail.invalid");
        // A new Google sign-in finds it by its email and links.
        var g=google("legacy-google","legacy@gmail.invalid");
        assertThat(registry().admit(g,proof()).accountId()).isEqualTo(account);
        String lp=proof();assertThat(registry().admit(legacy,lp).accountId()).isEqualTo(account);
        assertThat(jdbc.queryForMap("SELECT provider,link_source,verified_email FROM cloud_identities WHERE subject=?",legacy.subject()))
            .containsEntry("provider","cognito").containsEntry("link_source","backfill").containsEntry("verified_email","legacy@gmail.invalid");
        assertThat(registry().authenticate(legacy,lp).accountId()).isEqualTo(account);assertThat(identities(account)).isEqualTo(2);
    }
    @Test void theRuntimeRoleLinksWithTheV19Grants(){
        UUID account=admitted(google("runtime-google","runtime@gmail.invalid"));
        var p=password("runtime-password","runtime@gmail.invalid");String pp=proof();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var signIn=tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");return registry().admit(p,pp);});
        assertThat(signIn.accountId()).isEqualTo(account);assertThat(events(account,"link")).isEqualTo(1);
        for(String privilege:List.of("SELECT","INSERT","UPDATE","DELETE"))
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_identities',?)",Boolean.class,privilege)).as(privilege).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_identities','TRUNCATE')",Boolean.class)).isFalse();
    }
    @Test void theOperatorCommandRecordsAndFindsEveryIdentity(){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID account=CloudAccountAdministration.apply(jdbc,tx,new String[]{"admit",ISSUER,"operator-made","Google_777","Operator Made"});
        assertThat(jdbc.queryForMap("SELECT account_id,provider,link_source FROM cloud_identities WHERE subject='operator-made'"))
            .containsEntry("account_id",account).containsEntry("provider","google").containsEntry("link_source","first_contact");
        CloudIdentities.insert(jdbc,account,ISSUER,"operator-linked","operator@gmail.invalid","operator@gmail.invalid","linked");
        assertThat(CloudAccountAdministration.apply(jdbc,tx,new String[]{"author-on",ISSUER,"operator-linked"})).isEqualTo(account);
        assertThat(jdbc.queryForObject("SELECT author_access FROM cloud_accounts WHERE account_id=?",Boolean.class,account)).isTrue();
    }
    @Test void theCleanupRemovesEveryIdentityOfAnExpiredWaitingAccount(){
        UUID expired=contact(google("expiring-google","expiring@gmail.invalid"));
        assertThat(contact(password("expiring-password","expiring@gmail.invalid"))).isEqualTo(expired);
        UUID kept=admitted(google("kept-google","kept@gmail.invalid"));
        jdbc.update("UPDATE cloud_accounts SET requested_at=clock_timestamp()-interval '3 days' WHERE account_id IN (?,?)",expired,kept);
        assertThat(identities(expired)).isEqualTo(2);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        int removed=tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");return new CloudAdmissionCleanup(jdbc,new DataSourceTransactionManager(source)).expire();});
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(identities(expired)).isZero();assertThat(identities(kept)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id=?",Integer.class,expired)).isZero();
        // A release before V19 deletes only the account row; its identities go with it (ON DELETE CASCADE).
        UUID older=contact(google("older-release","older@gmail.invalid"));
        jdbc.update("DELETE FROM cloud_accounts WHERE account_id=? AND admitted=false",older);
        assertThat(identities(older)).isZero();
        // The same Gmail then starts again as a new waiting account.
        assertThat(contact(google("expiring-google-again","expiring@gmail.invalid"))).isNotEqualTo(expired);
    }
    /** V19 on a database that already has accounts: one identity per account, as the account's first identity. */
    @Test void theMigrationBackfillsOneIdentityPerExistingAccount()throws Exception {
        var before=new JdbcTemplate(schema("backfill_"));
        UUID googleId=UUID.randomUUID(),passwordId=UUID.randomUUID(),testId=UUID.randomUUID(),waitingId=UUID.randomUUID();
        for(UUID id:List.of(googleId,passwordId,testId,waitingId))before.update("INSERT INTO platform_subjects(id) VALUES (?)",id);
        before.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,'g-sub',true,'Google_123','G','g@gmail.invalid')",googleId,ISSUER);
        before.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name) VALUES (?,?,'p-sub',true,'p@example.invalid','P')",passwordId,ISSUER);
        before.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name) VALUES (?,?,?,true,'learner01@lookahead.dev','T')",testId,TestAccountRoster.TEST_ISSUER,testId.toString());
        // A username that only looks like Google's (no underscore) stays a password account.
        before.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,'w-sub',false,'googlex@example.invalid','W','w@example.invalid')",waitingId,ISSUER);
        before.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,action) VALUES (?,?,?,'w-sub','request')",UUID.randomUUID(),waitingId,ISSUER);
        before.execute(sql("V19__cloud_identities.sql"));
        assertThat(before.queryForObject("SELECT count(*) FROM cloud_identities",Integer.class)).isEqualTo(4);
        assertThat(before.queryForObject("SELECT count(*) FROM cloud_identities WHERE link_source<>'backfill'",Integer.class)).isZero();
        assertThat(before.queryForObject("""
            SELECT count(*) FROM cloud_identities i JOIN cloud_accounts a USING(account_id)
            WHERE i.issuer=a.issuer AND i.subject=a.subject AND i.username=a.username AND i.verified_email IS NOT DISTINCT FROM a.verified_email AND i.linked_at=a.requested_at
            """,Integer.class)).isEqualTo(4);
        Map<UUID,String> providers=new HashMap<>();
        before.query("SELECT account_id,provider FROM cloud_identities",(java.sql.ResultSet r)->{providers.put(r.getObject(1,UUID.class),r.getString(2));});
        assertThat(providers).containsExactlyInAnyOrderEntriesOf(Map.of(googleId,"google",passwordId,"cognito",testId,"test",waitingId,"cognito"));
        // The history accepts link and merge, nothing else new.
        for(String action:List.of("link","merge"))before.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,action) VALUES (?,?,?,'g-sub',?)",UUID.randomUUID(),googleId,ISSUER,action);
        assertThatThrownBy(()->before.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,action) VALUES (?,?,?,'g-sub','unlink')",UUID.randomUUID(),googleId,ISSUER))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->before.update("INSERT INTO cloud_identities(id,account_id,issuer,subject,username,provider,link_source) VALUES (?,?,?,'x','x','apple','linked')",UUID.randomUUID(),googleId,ISSUER))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->before.update("INSERT INTO cloud_identities(id,account_id,issuer,subject,username,provider,link_source) VALUES (?,?,?,'x','x','google','guess')",UUID.randomUUID(),googleId,ISSUER))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // The accounts' first identities are unchanged.
        assertThat(before.queryForObject("SELECT subject FROM cloud_accounts WHERE account_id=?",String.class,googleId)).isEqualTo("g-sub");
    }
}
