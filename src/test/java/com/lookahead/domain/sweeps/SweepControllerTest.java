package com.lookahead.domain.sweeps;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StaffPermissions;
import com.lookahead.domain.review.platform.PlatformReviewFixtures;
import com.lookahead.domain.review.platform.PlatformReviewService;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DLV-971: POST /api/v1/sweeps/run is the owner's alone; it answers what the three sweeps did. */
class SweepControllerTest {
    static final String RUN = "/api/v1/sweeps/run";
    @TempDir Path root;
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "author@lookahead.test", "Owner", true);
    final AccountPrincipal admin = new AccountPrincipal(UUID.randomUUID(), "admin@example.test", "Admin", true);
    final AccountPrincipal reviewer = new AccountPrincipal(UUID.randomUUID(), "reviewer@example.test", "Reviewer", true);
    final RuntimeSweeps sweeps = mock(RuntimeSweeps.class);
    AccountPrincipal actor;
    MockMvc http;
    PlatformReviewService service;

    @BeforeEach void setup() {
        var author = mock(LocalAuthorAccess.class);
        when(author.allowed(owner)).thenReturn(true);
        var mapper = JsonMapper.builder().build();
        service = PlatformReviewFixtures.service(root, author, mapper, "reviewer@example.test");
        http = MockMvcBuilders.standaloneSetup(new SweepController(sweeps, service))
                .setControllerAdvice(new AccountErrorHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == AccountPrincipal.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory b) { return actor; }
                }).build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder run(String body) {
        return post(RUN).contentType("application/json").content(body);
    }

    @Test void theOwnerRunsEachJobAndReadsItsCounts() throws Exception {
        actor = owner;
        when(sweeps.runNow(owner.accountId(), RuntimeSweeps.Job.ALL)).thenReturn(new RuntimeSweeps.Result("all", "2026-11-06T18:30:00Z", 2, true, "12", 1));
        http.perform(run("{\"job\":\"all\"}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.job").value("all"))
                .andExpect(jsonPath("data.ranAt").value("2026-11-06T18:30:00Z"))
                .andExpect(jsonPath("data.supportRequestsClosed").value(2))
                .andExpect(jsonPath("data.inboxMerged").value(true))
                .andExpect(jsonPath("data.inboxResult").value("12"))
                .andExpect(jsonPath("data.admissionsRemoved").value(1));
        when(sweeps.runNow(owner.accountId(), RuntimeSweeps.Job.SUPPORT_REQUESTS)).thenReturn(new RuntimeSweeps.Result("support-requests", "2026-11-06T18:31:00Z", 0, null, null, null));
        http.perform(run("{\"job\":\"support-requests\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("data.job").value("support-requests"))
                .andExpect(jsonPath("data.supportRequestsClosed").value(0))
                .andExpect(jsonPath("data.inboxMerged").value(nullValue()))
                .andExpect(jsonPath("data.inboxResult").value(nullValue()))
                .andExpect(jsonPath("data.admissionsRemoved").value(nullValue()));
        when(sweeps.runNow(owner.accountId(), RuntimeSweeps.Job.DELIVERY_INBOX)).thenReturn(new RuntimeSweeps.Result("delivery-inbox", "2026-11-06T18:32:00Z", null, false, "no inbox file", null));
        http.perform(run("{\"job\":\"delivery-inbox\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("data.job").value("delivery-inbox"))
                .andExpect(jsonPath("data.supportRequestsClosed").value(nullValue()))
                .andExpect(jsonPath("data.inboxMerged").value(false))
                .andExpect(jsonPath("data.inboxResult").value("no inbox file"));
        when(sweeps.runNow(owner.accountId(), RuntimeSweeps.Job.ADMISSIONS)).thenReturn(new RuntimeSweeps.Result("admissions", "2026-11-06T18:33:00Z", null, null, null, 3));
        http.perform(run("{\"job\":\"admissions\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("data.job").value("admissions"))
                .andExpect(jsonPath("data.admissionsRemoved").value(3));
        // A local Domain has no admission cleanup: the job answers 409 and the page says so.
        when(sweeps.runNow(owner.accountId(), RuntimeSweeps.Job.ADMISSIONS)).thenThrow(new com.lookahead.learning.content.exception.AccountFailure(409, "SWEEP_NOT_AVAILABLE", "The admission cleanup runs only in cloud mode; this Domain has none."));
        http.perform(run("{\"job\":\"admissions\"}")).andExpect(status().isConflict()).andExpect(jsonPath("code").value("SWEEP_NOT_AVAILABLE"));
        verify(sweeps, times(5)).runNow(eq(owner.accountId()), any());
    }

    @Test void anUnknownJobOrAnyOtherBodyIs400BeforeAnySweepRuns() throws Exception {
        actor = owner;
        for (String body : new String[]{"{\"job\":\"everything\"}", "{\"job\":\"ALL\"}", "{\"job\":\"\"}", "{\"job\":1}", "{}", "{\"job\":\"all\",\"force\":true}", "{\"jobs\":\"all\"}"})
            http.perform(run(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("SWEEP_JOB_INVALID"));
        http.perform(post(RUN)).andExpect(status().is4xxClientError());
        verifyNoInteractions(sweeps);
    }

    @Test void anAdminAReviewerAndAnonymousAreRefusedBeforeAnySweepRuns() throws Exception {
        var access = mock(StaffAccessService.class);
        when(access.permissions(admin)).thenReturn(StaffPermissions.forRoles(false, Set.of("admin")));
        when(access.permissions(reviewer)).thenReturn(StaffPermissions.forRoles(false, Set.of("reviewer")));
        when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true, Set.of()));
        service.configureStaffAccess(access);
        actor = admin;
        http.perform(run("{\"job\":\"all\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("PLATFORM_REVIEW_ACCESS_DENIED"));
        actor = reviewer;
        http.perform(run("{\"job\":\"support-requests\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("PLATFORM_REVIEW_ACCESS_DENIED"));
        actor = null;
        http.perform(run("{\"job\":\"all\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(sweeps);
        // With staff access configured the owner is the account that manages access, as for a triage.
        actor = owner;
        when(sweeps.runNow(owner.accountId(), RuntimeSweeps.Job.ALL)).thenReturn(new RuntimeSweeps.Result("all", "2026-11-06T18:30:00Z", 0, false, "no inbox file", 0));
        http.perform(run("{\"job\":\"all\"}")).andExpect(status().isOk()).andExpect(jsonPath("data.admissionsRemoved").value(0));
    }
}
