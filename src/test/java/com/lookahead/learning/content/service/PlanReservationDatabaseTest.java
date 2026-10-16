package com.lookahead.learning.content.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DLV921_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanReservationDatabaseTest {
    JdbcTemplate jdbc;String schema;UUID owner;ObjectMapper json=new ObjectMapper();
    Clock clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC);
    PlanLifecycle policy;
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("DLV921_DATABASE_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:4392/lookahead_domain_review");
        String password=Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
        schema="reservations_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password));
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        jdbc.execute("CREATE TABLE plans(id uuid PRIMARY KEY,account_id uuid NOT NULL,UNIQUE(account_id,id))");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V9__plan_lifecycle.sql")));
        policy=new PlanLifecycle(jdbc,json,clock);
    }
    @BeforeEach void account(){owner=UUID.randomUUID();}
    @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    UUID plan(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO plans VALUES (?,?)",id,owner);return id;}
    ObjectNode snapshot(String course,int hours,int days){
        var result=json.createObjectNode();var config=result.putObject("config").put("days",days).put("dailyHours",hours);
        config.putArray("topicIds").add(course);return result;
    }
    void activate(UUID id,String course,int hours,String start){policy.create(owner,id,snapshot(course,hours,7),json.getNodeFactory().textNode(start),false);}
    @Test void legacyReadsNeverInventDatesAndActivationIsExplicit(){
        UUID id=plan();assertThat(policy.view(owner,id).path("state").asText()).isEqualTo("unactivated");
        policy.replace(owner,id,snapshot("learn:a",1,7));
        assertThat(policy.view(owner,id).has("startDate")).isFalse();
        policy.create(owner,id,snapshot("learn:a",1,7),json.missingNode(),true);
        assertThatThrownBy(()->policy.create(owner,id,snapshot("learn:a",1,7),json.missingNode(),false)).hasMessageContaining("explicit UTC");
        assertThatThrownBy(()->activate(id,"learn:a",1,"2026-02-30")).hasMessageContaining("Invalid UTC");
        assertThatThrownBy(()->activate(id,"learn:a",1,"2020-01-01")).hasMessageContaining("archive date");
        activate(id,"learn:a",1,"2026-10-15");
        assertThat(policy.view(owner,id).path("startDate").asText()).isEqualTo("2026-10-15");
        assertThat(policy.view(UUID.randomUUID(),id).path("state").asText()).isEqualTo("unactivated");
    }
    @Test void fourthPlanAcceptedFifthRejectedAndSubjectsStayReservedDuringGrace(){
        for(int n=0;n<4;n++)activate(plan(),"learn:course"+n,1,"2026-10-01");
        assertThatThrownBy(()->activate(plan(),"learn:course4",1,"2026-10-15")).hasMessageContaining("four");
        UUID other=UUID.randomUUID();owner=other;
        activate(plan(),"learn:same",1,"2026-10-01");
        assertThatThrownBy(()->activate(plan(),"learn:same",1,"2026-11-01")).hasMessageContaining("only one ACTIVE");
    }
    @Test void capacityUsesOnlyOverlappingDatesAndReplacementExcludesOldReservation(){
        UUID a=plan(),b=plan();activate(a,"learn:a",9,"2026-10-15");activate(b,"learn:b",9,"2026-10-15");
        assertThatThrownBy(()->activate(plan(),"learn:c",1,"2026-10-15")).hasMessageContaining("1080");
        activate(plan(),"learn:c",15,"2026-10-22");
        policy.replace(owner,a,snapshot("learn:a",9,7));
        assertThat(policy.view(owner,a).path("configuredDailyMinutes").asInt()).isEqualTo(540);
        assertThatThrownBy(()->policy.replace(owner,a,snapshot("learn:a",10,7))).hasMessageContaining("1080");
    }
    @Test void archiveCompletionRestoreAndAutomaticBoundaryPreserveDates(){
        UUID a=plan();activate(a,"learn:a",1,"2026-10-15");
        policy.transition(owner,a,snapshot("learn:a",1,7),"archived",json.missingNode());
        assertThat(policy.view(owner,a).path("state").asText()).isEqualTo("archived");
        assertThatThrownBy(()->policy.replace(owner,a,snapshot("learn:a",1,14))).hasMessageContaining("Activate");
        policy.transition(owner,a,snapshot("learn:a",1,7),"active",json.getNodeFactory().textNode("2026-11-01"));
        policy.replace(owner,a,snapshot("learn:a",1,14));
        assertThat(policy.view(owner,a).path("endDateExclusive").asText()).isEqualTo("2026-11-15");
        var later=new PlanLifecycle(jdbc,json,Clock.fixed(Instant.parse("2027-02-13T00:00:00Z"),ZoneOffset.UTC));
        assertThat(later.view(owner,a).path("state").asText()).isEqualTo("archived");
        assertThat(jdbc.queryForObject("SELECT state FROM plan_lifecycle WHERE plan_id=?",String.class,a)).isEqualTo("active");
        policy.transition(owner,a,snapshot("learn:a",1,14),"completed",json.missingNode());
        assertThat(policy.view(owner,a).path("state").asText()).isEqualTo("completed");
        activate(plan(),"learn:a",1,"2026-10-15");
        assertThatThrownBy(()->policy.transition(owner,a,snapshot("learn:a",1,14),"bad",json.missingNode())).hasMessageContaining("Invalid lifecycle");
        assertThatThrownBy(()->policy.transition(owner,plan(),snapshot("learn:a",1,14),"archived",json.missingNode())).hasMessageContaining("explicit dates");
    }
}
