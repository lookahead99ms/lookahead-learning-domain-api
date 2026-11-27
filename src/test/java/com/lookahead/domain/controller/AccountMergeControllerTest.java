package com.lookahead.domain.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.AccountMergeModels;
import com.lookahead.domain.security.handler.GlobalExceptionHandler;
import com.lookahead.domain.service.signin.AccountMergeService;
import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.util.AccountMergeRules;
import com.lookahead.domain.validator.AccountMergeRequestValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import software.amazon.awssdk.core.exception.SdkClientException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DLV-969 S36.6: the merge routes' status codes and shapes, on a stubbed service. */
class AccountMergeControllerTest {
    static final AccountPrincipal ACTOR=new AccountPrincipal(UUID.fromString("00000000-0000-4000-8000-000000000001"),"learner","Learner",true,false,"learner@gmail.invalid");
    static final UUID KEEP=UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final String TOKEN="A".repeat(43);
    final AccountMergeService merges=mock(AccountMergeService.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new AccountMergeController(merges,new AccountMergeRequestValidator())).setControllerAdvice(new AccountErrorHandler(),new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
            public boolean supportsParameter(MethodParameter parameter){return parameter.getParameterType()==AccountPrincipal.class;}
            public Object resolveArgument(MethodParameter parameter,ModelAndViewContainer container,NativeWebRequest request,WebDataBinderFactory binders){return ACTOR;}
        }).build();

    @Test void theRequestAlwaysAnswers202WithNothingInIt()throws Exception {
        mvc.perform(post("/api/v1/account/merge/request").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.data").isEmpty()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/account/merge/request")).andExpect(status().isAccepted());
        verify(merges,times(2)).request(ACTOR);
    }
    @Test void theLinkAnswersTheCandidatesOr404()throws Exception {
        when(merges.candidates(ACTOR,TOKEN)).thenReturn(new AccountMergeModels.Candidates("learner@gmail.invalid",Instant.parse("2026-11-04T10:30:00Z"),List.of(
            new AccountMergeModels.Candidate(KEEP,"Learner",List.of(Map.of("provider","google","email","learner@gmail.invalid")),true,Instant.parse("2026-11-01T00:00:00Z"),null,
                List.of(new AccountMergeModels.Plan(KEEP,"Java",25)),true))));
        mvc.perform(get("/api/v1/account/merge/"+TOKEN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email").value("learner@gmail.invalid"))
            .andExpect(jsonPath("$.data.candidates[0].accountId").value(KEEP.toString()))
            .andExpect(jsonPath("$.data.candidates[0].signInMethods[0].provider").value("google"))
            .andExpect(jsonPath("$.data.candidates[0].studyPlans[0].progressPercent").value(25))
            .andExpect(jsonPath("$.data.candidates[0].paidMembership").value(true))
            .andExpect(header().string("Cache-Control","no-store"));
        when(merges.candidates(ACTOR,"B".repeat(43))).thenThrow(AccountMergeRules.invalidToken());
        mvc.perform(get("/api/v1/account/merge/"+"B".repeat(43))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MERGE_TOKEN_INVALID"))
            .andExpect(jsonPath("$.path").value("/api/v1/account/merge/[token]"));
    }
    /** DLV-969 S36: a merge link's token never reaches a log line (or an error body), whichever handler logs the failure. */
    @Test void theTokenIsNeverLogged()throws Exception {
        String token="C1-_".repeat(10)+"abc";
        var logged=new ListAppender<ILoggingEvent>();logged.start();
        var loggers=List.of((Logger)LoggerFactory.getLogger("lookahead.failure"),
            (Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class));
        loggers.forEach(l->l.addAppender(logged));
        try{
            when(merges.candidates(ACTOR,token)).thenThrow(SdkClientException.create("synthetic outage"));
            mvc.perform(get("/api/v1/account/merge/"+token)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_STORAGE_UNAVAILABLE")).andExpect(jsonPath("$.path").value("/api/v1/account/merge/[token]"));
            when(merges.merge(ACTOR,token,KEEP)).thenThrow(new IllegalStateException("synthetic bug"));
            mvc.perform(post("/api/v1/account/merge/"+token).contentType(MediaType.APPLICATION_JSON).content("{\"keepAccountId\":\""+KEEP+"\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.path").value("/api/v1/account/merge/[token]"));
            var lines=logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).hasSize(2).allSatisfy(line->assertThat(line).contains("/api/v1/account/merge/[token]").doesNotContain(token));
        }finally{loggers.forEach(l->l.detachAppender(logged));}
        assertThat(EventLog.redact("/api/v1/account/merge/"+token+"/x")).isEqualTo("/api/v1/account/merge/[token]/x");
        assertThat(EventLog.redact("/api/v1/account/merge/request")).isEqualTo("/api/v1/account/merge/request");
        assertThat(EventLog.redact("/api/v1/account/merge/"+token+"A")).contains(token);
        assertThat(EventLog.redact(null)).isNull();
    }
    @Test void theMergeAnswersTheKeptAccountOrTheContractsFailures()throws Exception {
        when(merges.merge(ACTOR,TOKEN,KEEP)).thenReturn(new AccountMergeModels.Kept(KEEP));
        mvc.perform(post("/api/v1/account/merge/"+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"keepAccountId\":\""+KEEP+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.keptAccountId").value(KEEP.toString()));
        UUID other=UUID.randomUUID(),changed=UUID.randomUUID();
        when(merges.merge(ACTOR,TOKEN,other)).thenThrow(AccountMergeRules.keepNotCandidate());
        when(merges.merge(ACTOR,TOKEN,changed)).thenThrow(AccountMergeRules.conflict());
        mvc.perform(post("/api/v1/account/merge/"+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"keepAccountId\":\""+other+"\"}"))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("MERGE_KEEP_NOT_CANDIDATE"));
        mvc.perform(post("/api/v1/account/merge/"+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"keepAccountId\":\""+changed+"\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MERGE_CONFLICT"));
        for(String body:new String[]{"{}","[]","\"x\"","{\"keepAccountId\":1}","{\"keepAccountId\":\"not-a-uuid\"}","{\"keepAccountId\":\"1-1-1-1-1\"}",
                "{\"keepAccountId\":\""+KEEP+"\",\"extra\":true}"})
            mvc.perform(post("/api/v1/account/merge/"+TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MERGE_REQUEST"));
        mvc.perform(post("/api/v1/account/merge/"+TOKEN)).andExpect(status().isBadRequest());
        // An upper-case id is the same id.
        mvc.perform(post("/api/v1/account/merge/"+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"keepAccountId\":\""+KEEP.toString().toUpperCase()+"\"}"))
            .andExpect(status().isOk());
        verify(merges,times(2)).merge(ACTOR,TOKEN,KEEP);
        verify(merges,never()).merge(any(),any(),isNull());
    }
}
