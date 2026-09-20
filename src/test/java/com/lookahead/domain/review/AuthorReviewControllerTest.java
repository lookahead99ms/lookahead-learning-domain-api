package com.lookahead.domain.review;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.filter.AccountRequestLimitsFilter;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.lookahead.domain.review.ReviewModels.*;

class AuthorReviewControllerTest {
    AuthorReviewService reviews;MockMvc http;
    final AccountPrincipal actor=new AccountPrincipal(UUID.randomUUID(),"synthetic@example.test","Synthetic",true);
    final String path="/api/v1/author/review-artifacts/study-plan-review/events";
    final String body="{\"artifactVersion\":\"v1\",\"contentHash\":\""+"a".repeat(64)+"\",\"ticketId\":\"DLV-704\",\"decision\":\"APPROVE\",\"comment\":\"\"}";
    @BeforeEach void setup(){
        reviews=mock(AuthorReviewService.class);
        http=MockMvcBuilders.standaloneSetup(new AuthorReviewController(reviews)).setControllerAdvice(new AccountErrorHandler())
                .addFilters(new AccountRequestLimitsFilter()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(MethodParameter parameter){return parameter.getParameterType()==AccountPrincipal.class;}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest r,WebDataBinderFactory b){return actor;}
                }).build();
    }
    @Test void fieldsDeriveActorFromSecurityContextAndNewReceiptReturns201()throws Exception {
        var key=UUID.randomUUID();when(reviews.submit(eq(actor),eq("study-plan-review"),eq(key),any())).thenReturn(new Receipt(null,false,"PENDING_MAIN_RECONCILIATION"));
        http.perform(post(path).header("Idempotency-Key",key).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("data.reconciliationStatus").value("PENDING_MAIN_RECONCILIATION"));
        verify(reviews).submit(eq(actor),eq("study-plan-review"),eq(key),any());
    }
    @Test void forgedActorExtraFieldsAndMissingKeyCannotReachService()throws Exception {
        http.perform(post(path).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace("\"comment\":\"\"","\"comment\":\"\",\"actorId\":\"forged\""))).andExpect(status().isUnprocessableEntity());
        http.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        http.perform(post(path+"?actorId=forged").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(reviews);
    }
    @Test void duplicateRetryIs200AndConflictRetainsSafeCode()throws Exception {
        when(reviews.submit(any(),any(),any(),any())).thenReturn(new Receipt(null,true,"PENDING_MAIN_RECONCILIATION"));
        http.perform(post(path).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("data.replayed").value(true));
        when(reviews.submit(any(),any(),any(),any())).thenThrow(new AccountFailure(409,"REVIEW_IDEMPOTENCY_CONFLICT","Retry original"));
        http.perform(post(path).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("REVIEW_IDEMPOTENCY_CONFLICT"));
    }
    @Test void oversizedAndMalformedRequestsDoNotReachService()throws Exception {
        http.perform(post(path).contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
        http.perform(post(path).contentType("application/json").content("{" )).andExpect(status().isBadRequest());
        verifyNoInteractions(reviews);
    }
}
