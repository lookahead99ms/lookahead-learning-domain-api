package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.SupportFeedbackService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DLV-971: "accepted" and "recorded" receipts are 202; an "unconfirmed" receipt stays 503. */
class SupportControllerTest {
    static final String BODY = "{\"type\":\"problem\",\"subject\":\"Search hangs\",\"message\":\"Steps\",\"replyTo\":\"reply@example.test\",\"images\":[]}";
    final AccountPrincipal learner = new AccountPrincipal(UUID.randomUUID(), "learner@example.test", "Learner", true);
    final SupportFeedbackService support = mock(SupportFeedbackService.class);
    final MockMvc http = MockMvcBuilders.standaloneSetup(new SupportController(support)).setControllerAdvice(new AccountErrorHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == AccountPrincipal.class; }
                public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory b) { return learner; }
            }).build();

    @Test void receiptStatusesMapToAcceptedOrUnavailable() throws Exception {
        for (var expected : new Object[][]{{"accepted", 202}, {"recorded", 202}, {"unconfirmed", 503}}) {
            String reference = UUID.randomUUID().toString();
            when(support.submit(eq(learner), eq("request-" + expected[0]), any())).thenReturn(new SupportFeedbackService.Receipt(reference, (String) expected[0]));
            http.perform(post("/api/v1/support").header("Idempotency-Key", "request-" + expected[0]).contentType("application/json").content(BODY))
                    .andExpect(status().is((int) expected[1])).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("data.reference").value(reference)).andExpect(jsonPath("data.status").value(expected[0]));
        }
        verify(support, times(3)).submit(eq(learner), any(), any());
    }
}
