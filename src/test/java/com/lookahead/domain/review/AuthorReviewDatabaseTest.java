package com.lookahead.domain.review;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="DLV921_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorReviewDatabaseTest {
    @TempDir static Path directory;
    boolean createdRuntimeRole;
    String schema,url; JdbcTemplate jdbc; DriverManagerDataSource source;
    static DriverManagerDataSource httpSource;
    static ReviewArtifactCatalog httpCatalog;
    ReviewArtifactCatalog catalog; AuthorPreviewAccessService access;
    final ObjectMapper mapper=new ObjectMapper();
    @BeforeAll void start()throws Exception {
        String base=System.getenv("DLV921_DATABASE_URL");String password=Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
        schema="review_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(base,"postgres",password)).execute("CREATE SCHEMA "+schema);
        url=base+"?currentSchema="+schema;source=new DriverManagerDataSource(url,"postgres",password);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE platform_subjects(id uuid PRIMARY KEY)");
        createdRuntimeRole=jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='lookahead_platform_app'",Integer.class)==0;
        if(createdRuntimeRole)jdbc.execute("CREATE ROLE lookahead_platform_app NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO lookahead_platform_app");
        String migration=Files.readString(Path.of("src/main/resources/db/domain/V2__author_review_events.sql"));
        // PostgreSQL JDBC executes the complete migration, preserving the trigger's dollar quoting.
        jdbc.execute(migration);
        Path manifest=directory.resolve("manifest.json");Files.writeString(manifest,mapper.writeValueAsString(List.of(new Artifact("study-plan-review","dlv-704/review-2026-09-19.1","a".repeat(64),"DLV-704"))));
        catalog=new ReviewArtifactCatalog(manifest.toString(),mapper);access=mock(AuthorPreviewAccessService.class);
    }
    @AfterAll void stop(){if(jdbc!=null){jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(createdRuntimeRole)jdbc.execute("DROP ROLE lookahead_platform_app");}}
    AuthorReviewService service(){return new AuthorReviewService(access,catalog,jdbc,new DataSourceTransactionManager(source),mapper);}
    AccountPrincipal actor(){var id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);return new AccountPrincipal(id,"synthetic@example.test","Synthetic",true);}
    Submission submission(Decision decision,String comment,UUID prior){return new Submission("dlv-704/review-2026-09-19.1","a".repeat(64),"DLV-704",decision,comment,prior);}
    @Test void durableReceiptSurvivesServiceReconstructionAndSameRetryDoesNotAppend() {
        var actor=actor();var key=UUID.randomUUID();var request=submission(Decision.APPROVE,"Reviewed",null);
        var first=service().submit(actor,"study-plan-review",key,request);
        var second=service().submit(actor,"study-plan-review",key,request);
        assertThat(first.replayed()).isFalse();assertThat(second.replayed()).isTrue();assertThat(second.event()).isEqualTo(first.event());
        assertThat(first.event().actorId()).isEqualTo(actor.accountId());assertThat(first.event().recordedAt()).isNotNull();
        assertThat(second.reconciliationStatus()).isEqualTo("PENDING_MAIN_RECONCILIATION");
        assertThat(service().history(actor,"study-plan-review",50,null).entries()).containsExactly(first.event());
    }
    @Test void changedPayloadWithSameKeyAndStaleBindingAreRejected() {
        var actor=actor();var key=UUID.randomUUID();service().submit(actor,"study-plan-review",key,submission(Decision.APPROVE,"",null));
        assertThatThrownBy(()->service().submit(actor,"study-plan-review",key,submission(Decision.DECLINE,"Change required",null))).hasMessageContaining("original payload");
        for(var bad:List.of(new Submission("old","a".repeat(64),"DLV-704",Decision.APPROVE,"",null),new Submission("dlv-704/review-2026-09-19.1","b".repeat(64),"DLV-704",Decision.APPROVE,"",null),new Submission("dlv-704/review-2026-09-19.1","a".repeat(64),"DLV-705",Decision.APPROVE,"",null)))
            assertThatThrownBy(()->service().submit(actor,"study-plan-review",UUID.randomUUID(),bad)).hasMessageContaining("current review artifact");
        assertThat(service().history(actor,"study-plan-review",50,null).entries()).hasSize(1);
    }
    @Test void supersedingDecisionPreservesHistoryAndConcurrentStaleChangeFails() {
        var actor=actor();var first=service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.DECLINE,"Explain recovery",null));
        var second=service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"Now clear",first.event().eventId()));
        assertThat(second.event().supersedesEventId()).isEqualTo(first.event().eventId());
        assertThatThrownBy(()->service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.NEED_MORE,"Need evidence",first.event().eventId()))).hasMessageContaining("Reload review history");
        var page=service().history(actor,"study-plan-review",1,null);assertThat(page.entries()).containsExactly(second.event());
        assertThat(service().history(actor,"study-plan-review",1,page.nextCursor()).entries()).containsExactly(first.event());
    }
    @Test void crossAccountHistoryCursorAndSupersedesAreDenied() {
        var a=actor();var b=actor();var first=service().submit(a,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"",null));
        assertThat(service().history(b,"study-plan-review",50,null).entries()).isEmpty();
        assertThatThrownBy(()->service().history(b,"study-plan-review",50,first.event().eventId())).hasMessageContaining("unavailable");
        assertThatThrownBy(()->service().submit(b,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"",first.event().eventId()))).hasMessageContaining("Reload review history");
    }
    @Test void explainDecisionsAndBoundComments() {
        var actor=actor();for(var decision:List.of(Decision.DECLINE,Decision.NEED_MORE))assertThatThrownBy(()->service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(decision,"  ",null))).hasMessageContaining("Explain");
        for(String bad:List.of("x".repeat(2001),"<script>","bad\u0000"))assertThatThrownBy(()->service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,bad,null))).hasMessageContaining("invalid");
    }
    @Test void databaseRejectsUpdateAndDeleteEvenUsingMigrationOwner() {
        var actor=actor();var result=service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"",null));
        assertThatThrownBy(()->jdbc.update("UPDATE author_review_events SET comment='changed' WHERE event_id=?",result.event().eventId())).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("DELETE FROM author_review_events WHERE event_id=?",result.event().eventId())).hasMessageContaining("append-only");
        assertThat(service().history(actor,"study-plan-review",50,null).entries()).hasSize(1);
    }
    @Test void concurrentDuplicateReturnsOneEventAcrossServiceInstances()throws Exception {
        var actor=actor();var key=UUID.randomUUID();var request=submission(Decision.APPROVE,"",null);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var calls=new ArrayList<Future<Receipt>>();
            for(int n=0;n<2;n++)calls.add(executor.submit(()->{start.await();return service().submit(actor,"study-plan-review",key,request);}));
            start.countDown();var a=calls.get(0).get(10,TimeUnit.SECONDS);var b=calls.get(1).get(10,TimeUnit.SECONDS);
            assertThat(a.event().eventId()).isEqualTo(b.event().eventId());assertThat(a.replayed()).isNotEqualTo(b.replayed());
        }
    }
    @Test void concurrentDifferentFirstDecisionsAllowOnlyOneInitialEvent()throws Exception {
        var actor=actor();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var calls=new ArrayList<Future<Boolean>>();
            for(int n=0;n<2;n++)calls.add(executor.submit(()->{start.await();try{service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"",null));return true;}catch(AccountFailure failure){assertThat(failure.code()).isEqualTo("REVIEW_SUPERSESSION_CONFLICT");return false;}}));
            start.countDown();int accepted=0;for(var call:calls)if(call.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);assertThat(service().history(actor,"study-plan-review",50,null).entries()).hasSize(1);
        }
    }
    @Test void insertFailureRollsBackAndSameKeyCanRetry() {
        var actor=actor();var key=UUID.randomUUID();var request=submission(Decision.APPROVE,"",null);
        jdbc.execute("CREATE FUNCTION reject_review_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic insert failure'; END; $$");
        jdbc.execute("CREATE TRIGGER test_insert_failure BEFORE INSERT ON author_review_events FOR EACH ROW EXECUTE FUNCTION reject_review_insert()");
        try{assertThatThrownBy(()->service().submit(actor,"study-plan-review",key,request)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("DROP TRIGGER test_insert_failure ON author_review_events");jdbc.execute("DROP FUNCTION reject_review_insert()");}
        assertThat(service().history(actor,"study-plan-review",50,null).entries()).isEmpty();
        assertThat(service().submit(actor,"study-plan-review",key,request).replayed()).isFalse();
    }
    @Test void clockRegressionDoesNotReverseHistoryOrSupersession() {
        var actor=actor();var first=service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"",null));
        jdbc.execute("ALTER TABLE author_review_events ALTER COLUMN recorded_at SET DEFAULT '2000-01-01T00:00:00Z'::timestamptz");
        try {
            var second=service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.NEED_MORE,"Evidence",first.event().eventId()));
            var third=service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"Fixed",second.event().eventId()));
            assertThat(second.event().recordedAt()).isBefore(first.event().recordedAt());
            assertThat(third.event().recordedAt()).isEqualTo(second.event().recordedAt());
            var page=service().history(actor,"study-plan-review",1,null);assertThat(page.entries()).containsExactly(third.event());
            assertThat(service().history(actor,"study-plan-review",50,page.nextCursor()).entries()).containsExactly(second.event(),first.event());
        }finally{jdbc.execute("ALTER TABLE author_review_events ALTER COLUMN recorded_at SET DEFAULT clock_timestamp()");}
    }
    @Test void runtimeRoleCanAppendAndReadButCannotMutateOrTruncate()throws Exception {
        var actor=actor();
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET ROLE lookahead_platform_app");
            try(var insert=connection.prepareStatement("INSERT INTO author_review_events(event_id,artifact_id,artifact_version,content_hash,ticket_id,decision,comment,actor_id,idempotency_key,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                insert.setObject(1,UUID.randomUUID());insert.setString(2,"study-plan-review");insert.setString(3,"v1");
                insert.setString(4,"a".repeat(64));insert.setString(5,"DLV-704");insert.setString(6,"APPROVE");
                insert.setString(7,"");insert.setObject(8,actor.accountId());insert.setObject(9,UUID.randomUUID());
                insert.setString(10,"b".repeat(64));
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try(var rows=statement.executeQuery("SELECT count(*) FROM author_review_events")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isPositive();}
            for(String denied:List.of("UPDATE author_review_events SET comment='bad'","DELETE FROM author_review_events","TRUNCATE author_review_events"))
                assertThatThrownBy(()->statement.executeUpdate(denied)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("permission denied");
        }
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @org.springframework.context.annotation.Import({AuthorReviewController.class,AuthorReviewService.class,
        com.lookahead.learning.content.handler.AccountErrorHandler.class,
        com.lookahead.learning.content.service.AuthorPreviewAccessService.class,
        com.lookahead.learning.content.security.LocalAuthorAccess.class,
        com.lookahead.learning.content.repository.AccountRepository.class,
        com.lookahead.domain.security.DomainSecurityConfiguration.class})
    static class HttpApp {
        @org.springframework.context.annotation.Bean javax.sql.DataSource dataSource(){return httpSource;}
        @org.springframework.context.annotation.Bean JdbcTemplate jdbc(){return new JdbcTemplate(httpSource);}
        @org.springframework.context.annotation.Bean org.springframework.transaction.PlatformTransactionManager transactions(){return new DataSourceTransactionManager(httpSource);}
        @org.springframework.context.annotation.Bean ReviewArtifactCatalog catalog(){return httpCatalog;}
        @org.springframework.context.annotation.Bean com.lookahead.learning.content.validator.SnapshotValidator topics(){
            var topics=mock(com.lookahead.learning.content.validator.SnapshotValidator.class);when(topics.allTopicIds()).thenReturn(Set.of("synthetic-topic"));return topics;
        }
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary org.springframework.security.oauth2.jwt.JwtDecoder decoder(){
            return token->{
                String subject=token.equals("synthetic-author")?com.lookahead.learning.content.security.LocalAuthorAccess.ACCOUNT_ID.toString():"4f91d1b8-6622-4a74-89f1-f1cb6c6bc6bd";
                return org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token).header("alg","RS256").subject(subject).audience(List.of("lookahead-api")).claim("client_id","lookahead-web-gateway").claim("scope","account").issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(60)).build();
            };
        }
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary com.lookahead.domain.security.IdentityVerificationClient verifier(){
            var verifier=mock(com.lookahead.domain.security.IdentityVerificationClient.class);
            when(verifier.verify(anyString())).thenAnswer(invocation->{boolean author=invocation.getArgument(0).equals("synthetic-author");return new com.lookahead.domain.security.IdentityVerificationClient.Verification(true,author?com.lookahead.learning.content.security.LocalAuthorAccess.ACCOUNT_ID.toString():"4f91d1b8-6622-4a74-89f1-f1cb6c6bc6bd",author?com.lookahead.learning.content.security.LocalAuthorAccess.USERNAME:"synthetic-learner","Synthetic","lookahead-web-gateway");});return verifier;
        }
    }
    org.springframework.context.ConfigurableApplicationContext httpServer(){
        return new org.springframework.boot.builder.SpringApplicationBuilder(HttpApp.class).profiles("accounts","local-test","resource").properties(
            "spring.config.name=review-http-test","server.port=0","server.address=127.0.0.1","app.deployment-environment=local","app.local-test.author-enabled=true",
            "app.identity.issuer=http://127.0.0.1:4380","app.identity.upstream=http://127.0.0.1:4380","app.identity.gateway-client-id=lookahead-web-gateway","app.identity.verifier-secret=synthetic-verifier-secret-for-review-tests").run();
    }
    java.net.http.HttpResponse<String> http(org.springframework.context.ConfigurableApplicationContext server,String method,String path,String token,String body,UUID key)throws Exception {
        int port=((org.springframework.boot.web.server.context.WebServerApplicationContext)server).getWebServer().getPort();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path));
        if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key.toString());
        if(body!=null)request.header("Content-Type","application/json");
        return java.net.http.HttpClient.newHttpClient().send(request.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualHttpAuthorGateAndApplicationContextRestartPreserveRecordedEvent()throws Exception {
        httpSource=source;httpCatalog=catalog;
        UUID author=com.lookahead.learning.content.security.LocalAuthorAccess.ACCOUNT_ID;
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",author);
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_grants(account_id uuid,topic_id text,valid_until timestamptz)");
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)",author,"synthetic-topic");
        String path="/api/v1/author/review-artifacts/study-plan-review/events";UUID key=UUID.randomUUID();String body=mapper.writeValueAsString(submission(Decision.APPROVE,"Synthetic restart check",null));String event;
        try(var server=httpServer()) {
            assertThat(http(server,"GET","/api/v1/author/review-artifacts",null,null,null).statusCode()).isEqualTo(401);
            assertThat(http(server,"GET","/api/v1/author/review-artifacts","synthetic-learner",null,null).statusCode()).isEqualTo(403);
            assertThat(http(server,"POST",path,"synthetic-learner",body,key).statusCode()).isEqualTo(403);
            var response=http(server,"POST",path,"synthetic-author",body,key);assertThat(response.statusCode()).isEqualTo(201);
            event=mapper.readTree(response.body()).path("data").path("event").path("eventId").asText();assertThat(event).isNotBlank();
        }
        try(var restarted=httpServer()) {
            var replay=http(restarted,"POST",path,"synthetic-author",body,key);assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(replay.body()).path("data").path("event").path("eventId").asText()).isEqualTo(event);
            var history=http(restarted,"GET",path,"synthetic-author",null,null);assertThat(history.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(history.body()).path("data").path("entries").get(0).path("eventId").asText()).isEqualTo(event);
        }
    }
    @Test void absentAuthorCapabilityDeniesAllOperationsBeforeStorage() {
        var actor=actor();doThrow(new AccountFailure(403,"AUTHOR_PREVIEW_ACCESS_DENIED","denied")).when(access).requireAccess(actor);
        assertThatThrownBy(()->service().artifacts(actor)).hasMessage("denied");
        assertThatThrownBy(()->service().history(actor,"study-plan-review",50,null)).hasMessage("denied");
        assertThatThrownBy(()->service().submit(actor,"study-plan-review",UUID.randomUUID(),submission(Decision.APPROVE,"",null))).hasMessage("denied");
    }
}
