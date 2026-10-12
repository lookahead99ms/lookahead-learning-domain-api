package com.lookahead.domain.access;

import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffAccessDatabaseTest {
    JdbcTemplate jdbc; String schema; TransactionTemplate tx; StaffAccessRepository repository; StaffAccessService service;
    AccountPrincipal owner, learner, other;
    @BeforeAll void start() throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Disposable database required");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        schema="staff_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");
        jdbc.execute("CREATE FUNCTION reject_author_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable'; END; $$");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V6__staff_access.sql")));
        repository=new StaffAccessRepository(jdbc,new ObjectMapper());
        var catalog=mock(SnapshotValidator.class);when(catalog.allTopicIds()).thenReturn(Set.of("java","system-design"));
        service=new StaffAccessService(repository,new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment","dev")),catalog);
        owner=account(true);learner=account(false);other=account(false);
    }
    AccountPrincipal account(boolean owner) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects VALUES(?)",id);
        return new AccountPrincipal(id,"synthetic-"+id,"Synthetic",true,owner);
    }
    StaffAccessRepository.Assignment save(AccountPrincipal actor, StaffAccessService.Change change) {
        return tx.execute(status->service.save(actor,change));
    }
    @AfterAll void stop(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @Test void ownerOnlyRevisionCheckedAuditedAndRevocationTakesEffect() {
        UUID id=UUID.randomUUID();
        var change=new StaffAccessService.Change(id,learner.accountId(),null,Set.of("admin"),Set.of("java"),false,0);
        assertThatThrownBy(()->save(learner,change)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->save(owner,new StaffAccessService.Change(id,owner.accountId(),null,Set.of("admin"),Set.of(),false,0))).isInstanceOf(RuntimeException.class);
        assertThat(save(owner,change).revision()).isEqualTo(1);
        assertThat(service.permissions(learner).triageFeedback()).isTrue();
        assertThat(service.permissions(learner).deliveryDone()).isFalse();
        assertThat(service.permissions(other).triageFeedback()).isFalse();
        assertThatThrownBy(()->save(owner,change)).isInstanceOf(RuntimeException.class);
        save(owner,new StaffAccessService.Change(id,learner.accountId(),null,Set.of(),Set.of(),false,1));
        assertThat(service.permissions(learner).triageFeedback()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_access_events WHERE assignment_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','staff_access_events','UPDATE')",Boolean.class)).isFalse();
        assertThatThrownBy(()->jdbc.update("DELETE FROM staff_access_events WHERE assignment_id=?",id)).isInstanceOf(RuntimeException.class);
    }
    @Test void pendingClaimRequiresVerifiedIdentityAndNeverMergesAccountsOrReplays() {
        UUID id=UUID.randomUUID();var recipient=account(false);var impostor=account(false);
        save(owner,new StaffAccessService.Change(id,null,"Invited@Example.invalid",Set.of("tester"),Set.of(),false,0));
        tx.executeWithoutResult(status->service.claimVerified(recipient,null));
        assertThat(repository.forAccount(recipient.accountId())).isNull();
        tx.executeWithoutResult(status->service.claimVerified(impostor,"different@example.invalid"));
        assertThat(repository.forAccount(impostor.accountId())).isNull();
        tx.executeWithoutResult(status->service.claimVerified(recipient,"invited@example.invalid"));
        assertThat(service.permissions(recipient).fullCurriculum()).isTrue();
        assertThat(service.permissions(recipient).deliveryRead()).isFalse();
        tx.executeWithoutResult(status->service.claimVerified(impostor,"invited@example.invalid"));
        assertThat(repository.forAccount(impostor.accountId())).isNull();
        assertThat(repository.forAccount(recipient.accountId()).pendingEmail()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_access_events WHERE assignment_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void expiredAndCancelledInvitationsCannotClaimAccess() {
        var recipient=account(false);UUID id=UUID.randomUUID();
        save(owner,new StaffAccessService.Change(id,null,"expired@example.invalid",Set.of("reviewer"),Set.of(),false,0));
        jdbc.update("UPDATE staff_access SET updated_at=clock_timestamp()-interval '15 days' WHERE id=?",id);
        tx.executeWithoutResult(status->service.claimVerified(recipient,"expired@example.invalid"));
        assertThat(repository.forAccount(recipient.accountId())).isNull();
        save(owner,new StaffAccessService.Change(id,null,"expired@example.invalid",Set.of(),Set.of(),false,1));
        tx.executeWithoutResult(status->service.claimVerified(recipient,"expired@example.invalid"));
        assertThat(repository.forAccount(recipient.accountId())).isNull();
    }

}
