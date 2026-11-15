package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.validator.PlanReservationPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static com.lookahead.learning.content.util.PayloadReaders.*;

/** Called inside PlanService's owner-locked transaction. Reads never write or infer dates. DLV-970: rows sit behind {@link PlanLifecycleStore}. */
@Service @Profile("accounts")
public class PlanLifecycle {
    public record Entry(UUID id,String state,LocalDate start,int days,int minutes,Set<String> subjects) {
        LocalDate end(){return start.plusDays(days);}
        String effective(LocalDate today){return state.equals("active")&&!today.isBefore(end().plusDays(90))?"archived":state;}
        PlanReservationPolicy.Reservation reservation(){return new PlanReservationPolicy.Reservation(id,subjects,start,days,minutes);}
    }
    private final PlanLifecycleStore store;
    private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PlanLifecycle(PlanLifecycleStore store,ObjectMapper json){this(store,json,Clock.systemUTC());}
    public PlanLifecycle(PlanLifecycleStore store,ObjectMapper json,Clock clock){this.store=store;this.json=json;this.clock=clock;}
    /** PostgreSQL callers and tests: the rows on this JdbcTemplate. */
    public PlanLifecycle(JdbcTemplate jdbc,ObjectMapper json){this(new JdbcPlanLifecycleStore(jdbc,json),json);}
    public PlanLifecycle(JdbcTemplate jdbc,ObjectMapper json,Clock clock){this(new JdbcPlanLifecycleStore(jdbc,json),json,clock);}
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneOffset.UTC));}
    private List<Entry> entries(UUID owner){return store.entries(owner);}
    public ObjectNode view(UUID owner,UUID id){
        var row=entries(owner).stream().filter(e->e.id.equals(id)).findFirst().orElse(null);
        var result=json.createObjectNode().put("state",row==null?"unactivated":row.effective(today()));
        if(row!=null){result.put("startDate",row.start.toString());result.put("endDateExclusive",row.end().toString());
            result.put("archiveDate",row.end().plusDays(90).toString());result.put("configuredDailyMinutes",row.minutes);}
        return result;
    }
    public void create(UUID owner,UUID id,JsonNode snapshot,JsonNode start,boolean imported){
        if(imported&&(start.isMissingNode()||start.isNull()))return;
        reserve(owner,id,snapshot,date(start));
    }
    public void replace(UUID owner,UUID id,JsonNode snapshot){
        var old=entries(owner).stream().filter(e->e.id.equals(id)).findFirst().orElse(null);
        if(old==null)return; // Explicitly unactivated legacy; preserve its lack of dates.
        if(!old.effective(today()).equals("active"))throw new AccountFailure(409,"PLAN_INACTIVE","Activate this plan with explicit dates before changing its schedule");
        reserve(owner,id,snapshot,old.start);
    }
    public void transition(UUID owner,UUID id,JsonNode snapshot,String state,JsonNode start){
        require(Set.of("active","archived","completed").contains(state),"Invalid lifecycle state");
        if(state.equals("active")){reserve(owner,id,snapshot,date(start));return;}
        var old=entries(owner).stream().filter(e->e.id.equals(id)).findFirst().orElse(null);
        if(old==null)throw new AccountFailure(409,"PLAN_DATES_REQUIRED","Activate this undated plan with explicit dates first");
        store.setState(owner,id,state);
    }
    private LocalDate date(JsonNode input){
        require(input.isString()&&input.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"An explicit UTC startDate is required");
        try {return LocalDate.parse(input.asText());}catch(DateTimeException ex){throw new IllegalArgumentException("Invalid UTC startDate");}
    }
    private void reserve(UUID owner,UUID id,JsonNode snapshot,LocalDate start){
        var config=snapshot.path("config");int days=integer(config,"days",1,180);
        int minutes;
        try {minutes=new java.math.BigDecimal(config.path("dailyHours").asText()).multiply(java.math.BigDecimal.valueOf(60)).intValueExact();}
        catch(RuntimeException ex){throw new IllegalArgumentException("Configured hours must resolve to whole minutes");}
        var subjects=new TreeSet<String>();config.path("topicIds").forEach(x->subjects.add(x.asText()));
        require(!subjects.isEmpty()&&subjects.size()<=100,"Select at least one canonical course");
        require(minutes>0&&minutes<=900,"Invalid configured daily minutes");
        require(today().isBefore(start.plusDays(days).plusDays(90)),"Choose a schedule whose archive date is in the future");
        var active=entries(owner).stream().filter(e->e.effective(today()).equals("active")&&!e.id.equals(id)).map(Entry::reservation).toList();
        var result=new PlanReservationPolicy().validate(active,new PlanReservationPolicy.Reservation(id,subjects,start,days,minutes));
        if(!result.eligible()){
            var failure=result.violations().getFirst();
            throw new AccountFailure(409,"PLAN_"+failure.code().name(),failure.detail());
        }
        store.reserve(owner,id,start,days,minutes,subjects);
    }
}
