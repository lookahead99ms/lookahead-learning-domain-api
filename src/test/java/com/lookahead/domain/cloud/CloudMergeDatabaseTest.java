package com.lookahead.domain.cloud;

import com.lookahead.domain.access.AccountRemovalService;
import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.testaccounts.TestAccountRoster;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DLV-969 S36.6 on the disposable cloud-auth database, the full Domain schema (V1-V20): accounts that already share a
 * verified email are merged by the person through an emailed single-use link (V20 cloud_merge_tokens). The sign-in
 * pool is mocked and never called; email goes to an in-memory fake.
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudMergeDatabaseTest {
    static final String ISSUER="https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
    static final Pattern LINK=Pattern.compile("https://dev\\.devlookahead\\.com/account/merge\\?token=([A-Za-z0-9_-]{43})");
    record Sent(String to,String subject,String body) {}
    final List<Sent> outbox=new ArrayList<>();
    final EmailSender fake=(to,subject,body)->outbox.add(new Sent(to,subject,body));
    JdbcTemplate jdbc;DriverManagerDataSource source;String schema;CognitoSignInAccounts pool;UUID ownerId;AccountPrincipal owner;

    @BeforeAll void setup()throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        schema="merge_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
        source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO lookahead_platform_app");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
        for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql",
                "V7__cloud_account_preferences.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql","V11__free_plan_allowances.sql",
                "V12__created_test_accounts.sql","V13__real_account_maintenance.sql","V14__managed_test_purchase_purge_grants.sql","V15__retain_runtime_audit.sql",
                "V17__dev_access_admission.sql","V18__access_removal_and_staff_aliases.sql","V19__cloud_identities.sql","V20__cloud_merge_tokens.sql",
                "V21__learner_test_accounts_only.sql","V22__review_records_and_status_events.sql"))
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
        ownerId=admitted(identity("owner-subject","owner@example.invalid","owner@example.invalid"));
        jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE account_id=?",ownerId);
        owner=new AccountPrincipal(ownerId,"owner@example.invalid","Owner",true,true);
    }
    @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @BeforeEach void fresh(){outbox.clear();pool=mock(CognitoSignInAccounts.class);}

    CloudSignInRegistry registry(){return new CloudSignInRegistry(jdbc,new DataSourceTransactionManager(source));}
    StaffAccessService staff(){return new StaffAccessService(new StaffAccessRepository(jdbc,new ObjectMapper()),new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment","dev")),mock(SnapshotValidator.class));}
    AccountRemovalService removal(){
        var beans=new StaticListableBeanFactory();beans.addBean("cognitoSignInAccounts",pool);
        return new AccountRemovalService(jdbc,staff(),beans.getBeanProvider(CognitoSignInAccounts.class),new DataSourceTransactionManager(source));
    }
    CloudMergeService merges(){return new CloudMergeService(jdbc,new DataSourceTransactionManager(source),removal(),fake,"https://dev.devlookahead.com/");}
    com.lookahead.domain.access.AccountAdmissionService admission(){
        return new com.lookahead.domain.access.AccountAdmissionService(jdbc,staff(),new StaffAccessRepository(jdbc,new ObjectMapper()),new ObjectMapper());
    }
    /**
     * Runs {@code call} while another transaction holds {@code account}'s row lock and has written a V18 state for it,
     * then commits that transaction once {@code call} waits on the lock: the race a locking statement's join cannot see.
     */
    Throwable racing(UUID account,String state,java.util.concurrent.Callable<?> call)throws Exception {
        var holding=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var holder=threads.submit(()->{new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
                jdbc.queryForList("SELECT account_id FROM cloud_accounts WHERE account_id=? FOR UPDATE",UUID.class,account);
                jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,?,?)",account,state,ownerId);
                holding.countDown();
                try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();}
            });return null;});
            holding.await();
            var racer=threads.submit(call);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)==0&&System.nanoTime()<until)Thread.onSpinWait();
            release.countDown();holder.get(10,java.util.concurrent.TimeUnit.SECONDS);
            try{racer.get(10,java.util.concurrent.TimeUnit.SECONDS);return null;}
            catch(java.util.concurrent.ExecutionException failed){return failed.getCause();}
        }
    }
    static void isFailure(Throwable thrown,int status,String code){
        assertThat(thrown).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }
    static CognitoIdentity identity(String subject,String username,String email){return new CognitoIdentity(ISSUER,subject,UUID.randomUUID().toString(),username,"Name of "+subject,Instant.now(),email);}
    /** The same identity signing in again: a new token family. */
    static CognitoIdentity again(CognitoIdentity identity){return new CognitoIdentity(identity.issuer(),identity.subject(),UUID.randomUUID().toString(),identity.username(),identity.displayName(),Instant.now(),identity.verifiedEmail());}
    static String proof(){return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status,String code){
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class,f->{assertThat(f.status()).isEqualTo(status);assertThat(f.code()).isEqualTo(code);});
    }
    UUID accountOf(CognitoIdentity identity){return jdbc.queryForObject("SELECT account_id FROM cloud_identities WHERE issuer=? AND subject=?",UUID.class,identity.issuer(),identity.subject());}
    UUID contact(CognitoIdentity identity){failsWith(()->registry().admit(identity,proof()),403,"ACCOUNT_ADMISSION_REQUIRED");return accountOf(identity);}
    UUID admitted(CognitoIdentity identity){UUID id=contact(identity);jdbc.update("UPDATE cloud_accounts SET admitted=true,admitted_at=clock_timestamp() WHERE account_id=?",id);return id;}

    /** One account of a person: its identity, a live sign-in and the session principal it gives. */
    record Person(UUID id,CognitoIdentity identity,String proof,AccountPrincipal session) {}
    /**
     * An admitted account with {@code email}; {@code first=false} makes it a second account for an email that already has
     * one (its email was verified only after its first contact, as S36 linking would otherwise join them).
     */
    Person person(String subject,String username,String email,boolean first){
        UUID id=admitted(identity(subject,username,first?email:null));
        if(!first){
            jdbc.update("UPDATE cloud_identities SET verified_email=? WHERE account_id=?",email,id);
            jdbc.update("UPDATE cloud_accounts SET verified_email=? WHERE account_id=?",email,id);
        }
        var signedIn=identity(subject,username,email);String proof=proof();
        registry().admit(signedIn,proof);
        return new Person(id,signedIn,proof,registry().authenticate(signedIn,proof));
    }
    /** Two accounts of one Gmail: Google first, then email and password. */
    List<Person> duplicates(String name){
        String email=name+"@gmail.invalid";
        return List.of(person(name+"-google","Google_"+name,email,true),person(name+"-password",email,email,false));
    }
    String token(int index){
        var matcher=LINK.matcher(outbox.get(index).body());assertThat(matcher.find()).isTrue();return matcher.group(1);
    }
    UUID plan(UUID account,String name,int completed){
        UUID plan=UUID.randomUUID(),version=UUID.randomUUID();
        String snapshot="{\"config\":{\"topicIds\":[\"java\"],\"days\":2},\"days\":[{\"day\":1,\"assignments\":[{\"id\":\"a1\"},{\"id\":\"a2\"}]},{\"day\":2,\"assignments\":[{\"id\":\"a3\"},{\"id\":\"a4\"}]}]}";
        String progress="{\"completedSessionIds\":"+new ObjectMapper().writeValueAsString(List.of("a1","a2","a3","a4").subList(0,completed))+"}";
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            long number=jdbc.queryForObject("SELECT coalesce(max(plan_number),0)+1 FROM plans WHERE account_id=?",Long.class,account);
            jdbc.update("INSERT INTO plans(id,account_id,revision,current_version,goal,progress,name,plan_number) VALUES (?,?,1,?,?,?::jsonb,?,?)",plan,account,version,name,progress,name,number);
            jdbc.update("INSERT INTO plan_versions(id,account_id,plan_id,snapshot,snapshot_digest,membership,provenance,recovery,reason) VALUES (?,?,?,?::jsonb,'d','{}','{}','{}','created')",version,account,plan,snapshot);
        });
        return plan;
    }
    int tokens(String email){return jdbc.queryForObject("SELECT count(*) FROM cloud_merge_tokens WHERE lower(email)=lower(?)",Integer.class,email);}
    int liveSignIns(UUID account){return jdbc.queryForObject("SELECT count(*) FROM cloud_sign_ins WHERE account_id=? AND revoked_at IS NULL",Integer.class,account);}

    @Test void theSignInOffersTheMergeOnlyWhileAnotherAccountHasTheEmail(){
        var people=duplicates("offer");
        assertThat(registry().admit(again(people.get(0).identity()),proof()).mergeAvailable()).isTrue();
        assertThat(registry().admit(again(people.get(1).identity()),proof()).mergeAvailable()).isTrue();
        var single=person("offer-single","Google_single","single@gmail.invalid",true);
        assertThat(registry().admit(again(single.identity()),proof()).mergeAvailable()).isFalse();
        // Case-insensitive; a deleted or test account never counts.
        var upper=identity("offer-google","Google_offer","OFFER@gmail.invalid");
        assertThat(registry().admit(upper,proof()).mergeAvailable()).isTrue();
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)",people.get(1).id(),ownerId);
        assertThat(registry().admit(again(people.get(0).identity()),proof()).mergeAvailable()).isFalse();
        UUID test=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",test);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,?,true,'learner09@lookahead.dev','Learner09','single@gmail.invalid')",test,TestAccountRoster.TEST_ISSUER,test.toString());
        assertThat(registry().admit(again(single.identity()),proof()).mergeAvailable()).isFalse();
    }

    @Test void aRequestSendsOneLinkReplacesItOnRepeatAndStopsAtThreePerHour(){
        var people=duplicates("request");var merges=merges();String email="request@gmail.invalid";
        try(var log=com.lookahead.domain.LogCapture.start()){
            merges.request(people.get(1).session());
            // DLV-969 log: who asked and how many accounts share the email; never the address or the link.
            var line=log.only("merge.request");
            assertThat(line.getFormattedMessage()).isEqualTo("Merge link sent for account "+people.get(1).id()+": 2 accounts share its verified email; the link is single use and valid for 30 minutes");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("event.outcome","success").containsEntry("account.id",people.get(1).id()).containsEntry("count",2);
            log.containsNone(email,outbox.getFirst().body().substring(outbox.getFirst().body().indexOf("token=")+6,outbox.getFirst().body().indexOf("token=")+49));
        }
        assertThat(outbox).hasSize(1);assertThat(tokens(email)).isEqualTo(1);
        var sent=outbox.getFirst();
        assertThat(sent.to()).isEqualTo(email);assertThat(sent.subject()).isEqualTo("Confirm it's you to tidy up your Look Ahead account");
        assertThat(sent.body()).contains("https://dev.devlookahead.com/account/merge?token=").contains(CloudMergeService.WARNING)
            .contains("within 30 minutes").contains("If it wasn't you, ignore this email. Nothing changes.");
        String first=token(0);
        // Only the digest is stored.
        assertThat(jdbc.queryForObject("SELECT token_digest FROM cloud_merge_tokens WHERE lower(email)=lower(?)",String.class,email)).isEqualTo(CloudSignInRegistry.digest(first)).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT requested_by FROM cloud_merge_tokens WHERE lower(email)=lower(?)",UUID.class,email)).isEqualTo(people.get(1).id());
        Instant expires=merges.candidates(people.get(0).session(),first).expiresAt();
        assertThat(expires).isBetween(Instant.now().plusSeconds(29*60),Instant.now().plusSeconds(31*60));
        // A repeat within the hour sends the link again; the stored digest means a new token, with the same expiry,
        // and the earlier link stops working: one outstanding link per address.
        merges.request(people.get(0).session());
        assertThat(outbox).hasSize(2);String second=token(1);assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_merge_tokens WHERE lower(email)=lower(?) AND used_at IS NULL",Integer.class,email)).isEqualTo(1);
        assertThat(merges.candidates(people.get(0).session(),second).expiresAt()).isEqualTo(expires);
        failsWith(()->merges.candidates(people.get(0).session(),first),404,"MERGE_TOKEN_INVALID");
        merges.request(people.get(0).session());assertThat(outbox).hasSize(3);
        // The fourth in an hour: the same answer, no email, no new link; one WARN line on lookahead.limit says why.
        try(var log=com.lookahead.domain.LogCapture.start()){
            merges.request(people.get(1).session());
            var limit=log.only("merge.request");
            assertThat(limit.getLoggerName()).isEqualTo("lookahead.limit");assertThat(limit.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(limit.getFormattedMessage()).isEqualTo("Merge link not sent for account "+people.get(1).id()+": already 3 links for this email in the last hour (the caller still gets 202)");
            assertThat(com.lookahead.domain.LogCapture.keys(limit)).containsEntry("reason","MERGE_REQUEST_RATE_LIMITED").containsEntry("limit.name","merge-links").containsEntry("limit.max",3L).containsEntry("limit.window","1h");
            log.containsNone(email);
        }
        assertThat(outbox).hasSize(3);assertThat(tokens(email)).isEqualTo(3);
        // Rows older than a day are cleared on the next request; the limit then allows a send again.
        jdbc.update("UPDATE cloud_merge_tokens SET created_at=created_at-interval '2 days',expires_at=expires_at-interval '2 days' WHERE lower(email)=lower(?)",email);
        merges.request(people.get(1).session());
        assertThat(outbox).hasSize(4);assertThat(tokens(email)).isEqualTo(1);
    }

    @Test void nothingIsSentWhenThereIsNothingToMergeAndTheCallerCannotTell(){
        var merges=merges();
        var single=person("lonely-google","Google_lonely","lonely@gmail.invalid",true);
        try(var log=com.lookahead.domain.LogCapture.start()){
            merges.request(single.session());
            merges.request(new AccountPrincipal(single.id(),"x","x",true,false,null));
            merges.request(new AccountPrincipal(single.id(),"x","x",true,false," "));
            // DLV-969 log: every silent 202 is one WARN line with its reason, so the owner can tell why nothing arrived.
            assertThat(log.lines("merge.request")).extracting(line->com.lookahead.domain.LogCapture.keys(line).get("reason")).containsExactly("NOTHING_TO_MERGE","NO_VERIFIED_EMAIL","NO_VERIFIED_EMAIL");
            assertThat(log.lines("merge.request").getFirst().getFormattedMessage()).isEqualTo("Merge link not sent for account "+single.id()+": 1 account(s) carry this email, nothing to merge (the caller still gets 202)");
            assertThat(log.lines("merge.request")).allMatch(line->"refused".equals(com.lookahead.domain.LogCapture.keys(line).get("event.outcome"))&&line.getLevel()==ch.qos.logback.classic.Level.WARN);
            log.containsNone("lonely@gmail.invalid");
        }
        // A session whose email is not its account's (or a test session) never sends either.
        var people=duplicates("notmine");
        merges.request(new AccountPrincipal(single.id(),"x","x",true,false,"notmine@gmail.invalid"));
        UUID test=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",test);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,?,true,'learner08@lookahead.dev','Learner08','notmine@gmail.invalid')",test,TestAccountRoster.TEST_ISSUER,test.toString());
        merges.request(new AccountPrincipal(test,"learner08@lookahead.dev","Learner08",true,false,"notmine@gmail.invalid"));
        assertThat(outbox).isEmpty();assertThat(tokens("lonely@gmail.invalid")+tokens("notmine@gmail.invalid")).isZero();
        assertThat(people).hasSize(2);
    }

    @Test void anEmailOutageStillAnswersAndCostsOneSend(){
        var people=duplicates("outage");
        var staff=new StaffAccessService(new StaffAccessRepository(jdbc,new ObjectMapper()),new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment","dev")),mock(SnapshotValidator.class));
        var removal=new AccountRemovalService(jdbc,staff,new StaticListableBeanFactory().getBeanProvider(CognitoSignInAccounts.class),new DataSourceTransactionManager(source));
        var down=new CloudMergeService(jdbc,new DataSourceTransactionManager(source),removal,(to,subject,body)->{throw new IllegalStateException("SES down");},"https://dev.devlookahead.com");
        try(var log=com.lookahead.domain.LogCapture.start()){
            assertThatCode(()->down.request(people.get(0).session())).doesNotThrowAnyException();
            // DLV-969 log: the link exists but no email went out; exception type only, never its message.
            var line=log.only("merge.request");
            assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(line.getFormattedMessage()).isEqualTo("Merge link for account "+people.get(0).id()+" was created but the email was not sent (IllegalStateException); it counts as one of the 3 sends per hour and the caller still gets 202");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("event.outcome","failure").containsEntry("reason","EMAIL_NOT_SENT").containsEntry("error.type","IllegalStateException");
            log.containsNone("outage@gmail.invalid","SES down");
        }
        assertThat(tokens("outage@gmail.invalid")).isEqualTo(1);
    }

    @Test void theLinkListsTheCandidatesWithTheirPlansToACandidateOnly(){
        var people=duplicates("list");var merges=merges();
        UUID kept=plan(people.get(0).id(),"Java in 30 days",1),dropped=plan(people.get(1).id(),"Spring Boot",4);
        jdbc.update("INSERT INTO membership_plans(id,definition,price_cents) VALUES ('merge-paid','{}',4900) ON CONFLICT (id) DO NOTHING");
        jdbc.update("""
            INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,price_paid_cents,status,starts_at,ends_at,source,actor_id,provider)
            VALUES (?,?,'merge-paid','{}','f','k-list',4900,'succeeded',now()-interval '1 day',now()+interval '30 days','checkout',?,'mock')""",UUID.randomUUID(),people.get(1).id(),people.get(1).id());
        // An ended purchase is not an active one.
        jdbc.update("""
            INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,price_paid_cents,status,starts_at,ends_at,source,actor_id,provider)
            VALUES (?,?,'merge-paid','{}','f','k-old',4900,'succeeded',now()-interval '60 days',now()-interval '30 days','checkout',?,'mock')""",UUID.randomUUID(),people.get(0).id(),people.get(0).id());
        merges.request(people.get(0).session());String token=token(0);
        var view=merges.candidates(people.get(1).session(),token);
        assertThat(view.email()).isEqualTo("list@gmail.invalid");
        assertThat(view.candidates()).extracting(CloudMergeService.Candidate::accountId).containsExactly(people.get(0).id(),people.get(1).id());
        var google=view.candidates().get(0);var password=view.candidates().get(1);
        assertThat(google.displayName()).isEqualTo("Name of list-google");assertThat(google.admitted()).isTrue();
        assertThat(google.signInMethods()).isEqualTo(List.of(Map.of("provider","google","email","list@gmail.invalid")));
        assertThat(password.signInMethods()).isEqualTo(List.of(Map.of("provider","cognito","email","list@gmail.invalid")));
        assertThat(google.createdAt()).isNotNull().isBeforeOrEqualTo(password.createdAt());
        assertThat(google.lastSignInAt()).isNotNull();
        assertThat(google.studyPlans()).containsExactly(new CloudMergeService.Plan(kept,"Java in 30 days",25));
        assertThat(password.studyPlans()).containsExactly(new CloudMergeService.Plan(dropped,"Spring Boot",100));
        assertThat(google.paidMembership()).isFalse();assertThat(password.paidMembership()).isTrue();
        // Another person's session, an unknown or malformed link: 404, never the list.
        var stranger=person("list-stranger","Google_stranger","stranger@gmail.invalid",true);
        failsWith(()->merges.candidates(stranger.session(),token),404,"MERGE_TOKEN_INVALID");
        failsWith(()->merges.candidates(people.get(0).session(),"A".repeat(43)),404,"MERGE_TOKEN_INVALID");
        failsWith(()->merges.candidates(people.get(0).session(),"short"),404,"MERGE_TOKEN_INVALID");
        failsWith(()->merges.candidates(people.get(0).session(),null),404,"MERGE_TOKEN_INVALID");
        failsWith(()->merges.merge(stranger.session(),token,stranger.id()),404,"MERGE_TOKEN_INVALID");
        // A test account with the same email is never listed.
        UUID test=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",test);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,?,true,'learner07@lookahead.dev','Learner07','list@gmail.invalid')",test,TestAccountRoster.TEST_ISSUER,test.toString());
        assertThat(merges.candidates(people.get(0).session(),token).candidates()).hasSize(2);
        failsWith(()->merges.merge(people.get(0).session(),token,test),409,"MERGE_CONFLICT");
    }

    @Test void theMergeMovesTheSignInsDeletesTheOtherAccountAndEndsEverySession(){
        var people=duplicates("merge");var merges=merges();
        var keep=people.get(0);var drop=people.get(1);
        UUID keptPlan=plan(keep.id(),"Kept plan",2);plan(drop.id(),"Dropped plan",3);
        jdbc.update("INSERT INTO cloud_account_preferences(account_id,theme) VALUES (?,'dark')",drop.id());
        jdbc.update("INSERT INTO cloud_sign_in_challenges(token_digest,account_id,family_digest,binding_digest,created_at,expires_at,auth_at) VALUES (?,?,?,?,now(),now()+interval '5 minutes',now())",
            "c".repeat(64),keep.id(),"f".repeat(64),"b".repeat(64));
        merges.request(drop.session());String token=token(0);
        assertThat(liveSignIns(keep.id())).isPositive();assertThat(liveSignIns(drop.id())).isPositive();
        // Signed in as the account that goes, keeping the other.
        try(var log=com.lookahead.domain.LogCapture.start()){
            assertThat(merges.merge(drop.session(),token,keep.id())).isEqualTo(new CloudMergeService.Kept(keep.id()));
            // DLV-969 log, written after the commit: one line per account that went, one for the merge; IDs and counts only.
            var gone=log.only("account.merge-removed");
            assertThat(gone.getFormattedMessage()).isEqualTo("Account "+drop.id()+" merged into "+keep.id()+": 1 sign-in(s) moved to the kept account, its learning data erased and the account marked deleted; no sign-in pool call, the pool users now belong to the kept account");
            assertThat(com.lookahead.domain.LogCapture.keys(gone)).containsEntry("account.id",drop.id()).containsEntry("target.id",drop.id()).containsEntry("into",keep.id()).containsEntry("admitted",true).containsEntry("count",1);
            var merged=log.only("account.merge");
            assertThat(merged.getFormattedMessage()).isEqualTo("Accounts merged by account "+drop.id()+": kept "+keep.id()+", 1 other account(s) deleted into it; every sign-in of all of them ended and the link used up");
            assertThat(com.lookahead.domain.LogCapture.keys(merged)).containsEntry("event.outcome","success").containsEntry("target.id",keep.id()).containsEntry("count",1);
            assertThat(log.lines().indexOf(gone)).isLessThan(log.lines().indexOf(merged));
            log.containsNone("merge@gmail.invalid",token,"Google_merge");
        }
        assertThat(jdbc.queryForList("SELECT subject FROM cloud_identities WHERE account_id=? ORDER BY linked_at",String.class,keep.id())).containsExactly("merge-google","merge-password");
        assertThat(jdbc.queryForObject("SELECT link_source FROM cloud_identities WHERE subject='merge-password'",String.class)).isEqualTo("merge");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_identities WHERE account_id=?",Integer.class,drop.id())).isZero();
        assertThat(jdbc.queryForMap("SELECT state,changed_by FROM cloud_account_states WHERE account_id=?",drop.id())).containsEntry("state","removed").containsEntry("changed_by",drop.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_account_states WHERE account_id=?",Integer.class,keep.id())).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM plans WHERE account_id=?",UUID.class,keep.id())).containsExactly(keptPlan);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plans WHERE account_id=?",Integer.class,drop.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_account_preferences WHERE account_id=?",Integer.class,drop.id())).isZero();
        assertThat(liveSignIns(keep.id())).isZero();assertThat(liveSignIns(drop.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT cancelled FROM cloud_sign_in_challenges WHERE account_id=?",Boolean.class,keep.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM cloud_merge_tokens WHERE token_digest=?",Boolean.class,CloudSignInRegistry.digest(token))).isTrue();
        var event=jdbc.queryForMap("SELECT issuer,subject,actor_id,roles::text roles FROM cloud_admission_events WHERE account_id=? AND action='merge'",drop.id());
        assertThat(event).containsEntry("subject","merge-password").containsEntry("actor_id",drop.id());
        assertThat(new ObjectMapper().readTree((String)event.get("roles")).path("into").asString()).isEqualTo(keep.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action='merge'",Integer.class,keep.id())).isZero();
        // The deleted row stays; both sessions are gone; both sign-in methods now reach the kept account.
        assertThat(jdbc.queryForObject("SELECT admitted FROM cloud_accounts WHERE account_id=?",Boolean.class,drop.id())).isTrue();
        assertThatThrownBy(()->registry().authenticate(keep.identity(),keep.proof())).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(()->registry().authenticate(drop.identity(),drop.proof())).isInstanceOf(AccountFailure.class);
        var again=registry().admit(again(drop.identity()),proof());
        assertThat(again.accountId()).isEqualTo(keep.id());assertThat(again.mergeAvailable()).isFalse();
        assertThat(CloudIdentities.usernames(jdbc,keep.id(),"x")).containsExactly("Google_merge","merge@gmail.invalid");
        // The link is single use; no sign-in pool call at all. Each refusal is one WARN line with its code, never the token.
        try(var log=com.lookahead.domain.LogCapture.start()){
            failsWith(()->merges.merge(keep.session(),token,keep.id()),404,"MERGE_TOKEN_INVALID");
            failsWith(()->merges.candidates(keep.session(),token),404,"MERGE_TOKEN_INVALID");
            assertThat(log.only("merge.merge").getFormattedMessage()).isEqualTo("Merge refused for account "+keep.id()+": MERGE_TOKEN_INVALID");
            assertThat(log.only("merge.candidates").getFormattedMessage()).isEqualTo("Merge link opened refused for account "+keep.id()+": MERGE_TOKEN_INVALID");
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("merge.merge"))).containsEntry("event.outcome","refused").containsEntry("reason","MERGE_TOKEN_INVALID").containsEntry("account.id",keep.id());
            assertThat(log.lines()).allMatch(line->line.getLevel()==ch.qos.logback.classic.Level.WARN);
            log.containsNone(token,"merge@gmail.invalid");
        }
        verifyNoInteractions(pool);
    }

    @Test void aCandidateDisabledSinceTheListIsAConflictAndAnUnknownKeepIsRefused(){
        var people=duplicates("conflict");var merges=merges();
        merges.request(people.get(0).session());String token=token(0);
        assertThat(merges.candidates(people.get(0).session(),token).candidates()).hasSize(2);
        failsWith(()->merges.merge(people.get(0).session(),token,UUID.randomUUID()),422,"MERGE_KEEP_NOT_CANDIDATE");
        failsWith(()->merges.merge(people.get(0).session(),token,null),422,"MERGE_KEEP_NOT_CANDIDATE");
        var stranger=person("conflict-stranger","Google_cs","cs@gmail.invalid",true);
        failsWith(()->merges.merge(people.get(0).session(),token,stranger.id()),422,"MERGE_KEEP_NOT_CANDIDATE");
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",people.get(1).id(),ownerId);
        failsWith(()->merges.merge(people.get(0).session(),token,people.get(0).id()),409,"MERGE_CONFLICT");
        failsWith(()->merges.candidates(people.get(0).session(),token),409,"MERGE_CONFLICT");
        // Nothing changed and the link is still live.
        assertThat(jdbc.queryForObject("SELECT used_at IS NULL FROM cloud_merge_tokens WHERE token_digest=?",Boolean.class,CloudSignInRegistry.digest(token))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_identities WHERE account_id=?",Integer.class,people.get(1).id())).isEqualTo(1);
        // The operator's enabled=false too; and a deleted candidate kept is a conflict, not "not a candidate".
        jdbc.update("DELETE FROM cloud_account_states WHERE account_id=?",people.get(1).id());
        jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE account_id=?",people.get(1).id());
        failsWith(()->merges.merge(people.get(0).session(),token,people.get(0).id()),409,"MERGE_CONFLICT");
        jdbc.update("UPDATE cloud_accounts SET enabled=true WHERE account_id=?",people.get(1).id());
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)",people.get(1).id(),ownerId);
        failsWith(()->merges.merge(people.get(0).session(),token,people.get(1).id()),409,"MERGE_CONFLICT");
        // Only one account left: nothing to merge.
        failsWith(()->merges.merge(people.get(0).session(),token,people.get(0).id()),409,"MERGE_CONFLICT");
    }

    @Test void anExpiredLinkIsInvalid(){
        var people=duplicates("expired");var merges=merges();
        merges.request(people.get(0).session());String token=token(0);
        jdbc.update("UPDATE cloud_merge_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE token_digest=?",CloudSignInRegistry.digest(token));
        failsWith(()->merges.candidates(people.get(0).session(),token),404,"MERGE_TOKEN_INVALID");
        failsWith(()->merges.merge(people.get(0).session(),token,people.get(0).id()),404,"MERGE_TOKEN_INVALID");
        // A new request is a fresh 30 minutes.
        merges.request(people.get(0).session());
        assertThat(merges.candidates(people.get(0).session(),token(1)).expiresAt()).isAfter(Instant.now().plusSeconds(29*60));
    }

    @Test void aWaitingAccountCanGoButNotBeKeptAndAnAuthorAccountIsNeverTheOneThatGoes(){
        var merges=merges();
        var admittedOne=person("waiting-google","Google_waiting","waiting@gmail.invalid",true);
        UUID waiting=contact(identity("waiting-password","waiting@gmail.invalid",null));
        jdbc.update("UPDATE cloud_identities SET verified_email='waiting@gmail.invalid' WHERE account_id=?",waiting);
        merges.request(admittedOne.session());String token=token(0);
        var listed=merges.candidates(admittedOne.session(),token).candidates();
        assertThat(listed).extracting(CloudMergeService.Candidate::admitted).containsExactly(true,false);
        assertThat(listed.get(1).lastSignInAt()).isNull();
        failsWith(()->merges.merge(admittedOne.session(),token,waiting),422,"MERGE_KEEP_NOT_CANDIDATE");
        // The owner sees the waiting row with its sign-in methods until the merge.
        var shown=admission().waiting(owner).waiting().stream().filter(w->w.accountId().equals(waiting)).findFirst().orElseThrow();
        assertThat(shown.signInMethods()).containsExactly(Map.of("provider","cognito","email","waiting@gmail.invalid"));
        // Old enough for the 48-hour cleanup: the cleanup of a release before this one (the same query, no state filter).
        jdbc.update("UPDATE cloud_accounts SET requested_at=clock_timestamp()-interval '3 days' WHERE account_id=?",waiting);
        merges.merge(admittedOne.session(),token,admittedOne.id());
        // Deleted outright as the cleanup deletes: no V18 state row, no account row, no subject; the identity moved first.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_account_states WHERE account_id=?",Integer.class,waiting)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_accounts WHERE account_id=?",Integer.class,waiting)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_subjects WHERE id=?",Integer.class,waiting)).isZero();
        assertThat(accountOf(identity("waiting-password","x",null))).isEqualTo(admittedOne.id());
        var event=jdbc.queryForMap("SELECT subject,actor_id,roles::text roles FROM cloud_admission_events WHERE account_id=? AND action='merge'",waiting);
        assertThat(event).containsEntry("subject","waiting-password").containsEntry("actor_id",admittedOne.id());
        assertThat(new ObjectMapper().readTree((String)event.get("roles")).path("into").asString()).isEqualTo(admittedOne.id().toString());
        var expired=jdbc.queryForList("SELECT account_id FROM cloud_accounts WHERE NOT admitted AND requested_at < clock_timestamp() - make_interval(hours => 48)",UUID.class);
        assertThat(expired).doesNotContain(waiting);
        assertThatCode(()->new CloudAdmissionCleanup(jdbc,new DataSourceTransactionManager(source)).expire()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action='expire'",Integer.class,waiting)).isZero();
        assertThat(admission().waiting(owner).waiting()).extracting(com.lookahead.domain.access.AccountAdmissionService.Waiting::accountId).doesNotContain(waiting);

        var authors=duplicates("author");
        jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE account_id=?",authors.get(0).id());
        merges.request(authors.get(1).session());String second=token(1);
        failsWith(()->merges.merge(authors.get(1).session(),second,authors.get(1).id()),422,"MERGE_KEEP_NOT_CANDIDATE");
        assertThat(merges.merge(authors.get(1).session(),second,authors.get(0).id()).keptAccountId()).isEqualTo(authors.get(0).id());
    }

    @Test void aDeleteRetryOnAMergedAwayAccountTellsThePoolNothingAndTheKeptUserIsUntouched(){
        var people=duplicates("retry");var merges=merges();
        merges.request(people.get(0).session());
        merges.merge(people.get(0).session(),token(0),people.get(0).id());
        // Its own identity now belongs to the kept account, so it holds no Cognito user any more.
        assertThat(CloudIdentities.usernames(jdbc,people.get(1).id(),"retry@gmail.invalid")).isEmpty();
        assertThat(removal().remove(owner,people.get(1).id(),new AccountRemovalService.Confirmation("DELETE")).state()).isEqualTo("removed");
        verifyNoInteractions(pool);
        assertThat(CloudIdentities.usernames(jdbc,people.get(0).id(),"Google_retry")).containsExactly("Google_retry","retry@gmail.invalid");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action='remove'",Integer.class,people.get(1).id())).isZero();
    }

    @Test void theOwnerNeverAdmitsAnAccountThatHasAState(){
        UUID waiting=contact(identity("stated-waiting","stated@gmail.invalid","stated@gmail.invalid"));
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",waiting,ownerId);
        failsWith(()->admission().admit(owner,new com.lookahead.domain.access.AccountAdmissionService.Request(waiting.toString(),List.of())),409,"ACCOUNT_REMOVED");
        assertThat(jdbc.queryForObject("SELECT admitted FROM cloud_accounts WHERE account_id=?",Boolean.class,waiting)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action='admit'",Integer.class,waiting)).isZero();
    }

    @Test void theOfferNeedsTwoAccountsNoneDisabled(){
        var people=duplicates("blocked");
        assertThat(registry().admit(again(people.get(0).identity()),proof()).mergeAvailable()).isTrue();
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",people.get(1).id(),ownerId);
        assertThat(registry().admit(again(people.get(0).identity()),proof()).mergeAvailable()).isFalse();
        jdbc.update("DELETE FROM cloud_account_states WHERE account_id=?",people.get(1).id());
        jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE account_id=?",people.get(1).id());
        assertThat(registry().admit(again(people.get(0).identity()),proof()).mergeAvailable()).isFalse();
        jdbc.update("UPDATE cloud_accounts SET enabled=true WHERE account_id=?",people.get(1).id());
        assertThat(registry().admit(again(people.get(0).identity()),proof()).mergeAvailable()).isTrue();
    }

    @Test void aSignInBringsTheProvidersCurrentEmailSoTheOldOneNoLongerMatches(){
        var people=duplicates("moved");var merges=merges();
        var changed=new CognitoIdentity(ISSUER,"moved-google",UUID.randomUUID().toString(),"Google_moved","Name of moved-google",Instant.now(),"moved-new@gmail.invalid");
        assertThat(registry().admit(changed,proof()).mergeAvailable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT verified_email FROM cloud_identities WHERE subject='moved-google'",String.class)).isEqualTo("moved-new@gmail.invalid");
        assertThat(jdbc.queryForObject("SELECT verified_email FROM cloud_accounts WHERE account_id=?",String.class,people.get(0).id())).isEqualTo("moved-new@gmail.invalid");
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,people.get(1).id(),"moved@gmail.invalid")).isZero();
        assertThat(registry().admit(again(people.get(1).identity()),proof()).mergeAvailable()).isFalse();
        merges.request(people.get(1).session());
        assertThat(outbox).isEmpty();
        // A sign-in that is not the account's first identity changes only its own row.
        var second=new CognitoIdentity(ISSUER,"moved-second",UUID.randomUUID().toString(),"moved-second@gmail.invalid","Second",Instant.now(),"moved-new@gmail.invalid");
        assertThat(registry().admit(second,proof()).accountId()).isEqualTo(people.get(0).id());
        var renamed=new CognitoIdentity(ISSUER,"moved-second",UUID.randomUUID().toString(),"moved-second@gmail.invalid","Second",Instant.now(),"moved-other@gmail.invalid");
        registry().admit(renamed,proof());
        assertThat(jdbc.queryForObject("SELECT verified_email FROM cloud_identities WHERE subject='moved-second'",String.class)).isEqualTo("moved-other@gmail.invalid");
        assertThat(jdbc.queryForObject("SELECT verified_email FROM cloud_accounts WHERE account_id=?",String.class,people.get(0).id())).isEqualTo("moved-new@gmail.invalid");
    }

    @Test void aStateWrittenWhileTheMergeWaitsOnTheRowsIsAConflictNotAnOutage()throws Exception {
        var merges=merges();
        // The kept account is disabled between the list and the merge.
        var first=duplicates("between");merges.request(first.get(0).session());String token=token(0);
        assertThat(merges.candidates(first.get(0).session(),token).candidates()).hasSize(2);
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)",first.get(1).id(),ownerId);
        failsWith(()->merges.merge(first.get(0).session(),token,first.get(1).id()),409,"MERGE_CONFLICT");
        // A Disable committed while the merge waits on the kept account's lock.
        var kept=duplicates("racekeep");merges.request(kept.get(0).session());String keepToken=token(1);
        isFailure(racing(kept.get(0).id(),"disabled",()->merges.merge(kept.get(0).session(),keepToken,kept.get(0).id())),409,"MERGE_CONFLICT");
        // ... and on the other account's lock: without the re-read its 'removed' insert would be a duplicate (a 503).
        var dropped=duplicates("racedrop");merges.request(dropped.get(0).session());String dropToken=token(2);
        isFailure(racing(dropped.get(1).id(),"disabled",()->merges.merge(dropped.get(0).session(),dropToken,dropped.get(0).id())),409,"MERGE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT state FROM cloud_account_states WHERE account_id=?",String.class,dropped.get(1).id())).isEqualTo("disabled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_identities WHERE account_id=?",Integer.class,dropped.get(1).id())).isEqualTo(1);
        // The owner's Disable racing another Disable: one state, no duplicate failure, no second event.
        var target=person("race-owner","Google_race_owner","race-owner@gmail.invalid",true);
        assertThat(racing(target.id(),"disabled",()->removal().disable(owner,target.id(),new AccountRemovalService.Confirmation("DISABLE")))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cloud_admission_events WHERE account_id=? AND action='disable'",Integer.class,target.id())).isZero();
        verify(pool).disable("Google_race_owner");
    }

    @Test void anAccountFromBeforeV19KeepsItsFirstSignInWhenMerged(){
        var merges=merges();
        // Both rows as a release before V19 wrote them: no identity rows.
        UUID older=UUID.randomUUID(),newer=UUID.randomUUID();
        for(UUID id:List.of(older,newer))jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,'legacy-1',true,'Google_legacy','Legacy','legacy@gmail.invalid')",older,ISSUER);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name,verified_email) VALUES (?,?,'legacy-2',true,'legacy@gmail.invalid','Legacy 2','legacy@gmail.invalid')",newer,ISSUER);
        var session=new AccountPrincipal(older,"Google_legacy","Legacy",true,false,"legacy@gmail.invalid");
        merges.request(session);String token=token(0);
        assertThat(merges.candidates(session,token).candidates()).extracting(c->c.signInMethods().getFirst().get("provider")).containsExactlyInAnyOrder("google","cognito");
        merges.merge(session,token,older);
        assertThat(jdbc.queryForMap("SELECT link_source FROM cloud_identities WHERE subject='legacy-1'")).containsEntry("link_source","backfill");
        assertThat(jdbc.queryForMap("SELECT account_id,link_source FROM cloud_identities WHERE subject='legacy-2'")).containsEntry("account_id",older).containsEntry("link_source","merge");
    }

    @Test void theRuntimeRoleRequestsAndMergesWithItsGrants(){
        var people=duplicates("runtime");var merges=merges();
        plan(people.get(1).id(),"Runtime plan",1);
        jdbc.update("INSERT INTO staff_access(id,account_id,roles) VALUES (?,?,'[\"tester\"]')",UUID.randomUUID(),people.get(1).id());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");merges.request(people.get(0).session());});
        String token=token(0);
        var kept=tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");merges.candidates(people.get(0).session(),token);return merges.merge(people.get(0).session(),token,people.get(0).id());});
        assertThat(kept.keptAccountId()).isEqualTo(people.get(0).id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plans WHERE account_id=?",Integer.class,people.get(1).id())).isZero();
        assertThat(jdbc.queryForObject("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,people.get(1).id())).isEqualTo("[]");
        for(String privilege:List.of("SELECT","INSERT","UPDATE","DELETE"))
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_merge_tokens',?)",Boolean.class,privilege)).as(privilege).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_merge_tokens','TRUNCATE')",Boolean.class)).isFalse();
    }
}
