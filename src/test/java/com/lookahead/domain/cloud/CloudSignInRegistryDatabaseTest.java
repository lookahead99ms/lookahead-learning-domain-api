package com.lookahead.domain.cloud;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

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
        jdbc.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V4__cloud_identity_and_sign_ins.sql")));
        registry=service();
    }
    CloudSignInRegistry service(){return new CloudSignInRegistry(jdbc,new DataSourceTransactionManager(source));}
    @AfterAll void stop(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    CognitoIdentity identity(String subject){return new CognitoIdentity(issuer,subject,UUID.randomUUID().toString(),"same@example.invalid","Learner",Instant.now());}
    UUID provision(CognitoIdentity identity){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects VALUES(?)",id);jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name) VALUES(?,?,?,true,?,?)",id,identity.issuer(),identity.subject(),identity.username(),identity.displayName());return id;}
    String proof(){return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
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
    @Test void applicationRoleCannotElevateAdmissionOrAuthor(){
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','cloud_accounts','INSERT')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','enabled','UPDATE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','admitted','UPDATE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('lookahead_platform_app','cloud_accounts','author_access','UPDATE')",Boolean.class)).isFalse();
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
        assertThatThrownBy(()->tx.execute(status->{jdbc.execute("SET LOCAL ROLE lookahead_platform_app");jdbc.update("UPDATE cloud_accounts SET admitted=true WHERE issuer=? AND subject=?",issuer,identity.subject());return null;}))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
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
