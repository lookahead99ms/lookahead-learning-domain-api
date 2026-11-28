package com.lookahead.domain.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.job.MaintenanceJobBeanRegistry;
import com.lookahead.domain.job.SweepRunner;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.handler.GlobalExceptionHandler;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.review.PlatformReviewFixtures;
import com.lookahead.domain.service.review.PlatformReviewService;
import com.lookahead.domain.validator.SweepRequestValidator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.json.JsonMapper;

/** DLV-971: POST /api/v1/sweeps/run is the owner's alone; it answers what the three sweeps did. */
class SweepControllerTest {
  static final String RUN = "/api/v1/sweeps/run";
  @TempDir Path root;
  final AccountPrincipal owner =
      new AccountPrincipal(UUID.randomUUID(), "author@lookahead.test", "Owner", true);
  final AccountPrincipal admin =
      new AccountPrincipal(UUID.randomUUID(), "admin@example.test", "Admin", true);
  final AccountPrincipal reviewer =
      new AccountPrincipal(UUID.randomUUID(), "reviewer@example.test", "Reviewer", true);

  /**
   * The real runner (it answers the three refusals since DLV-1016), with only the run itself
   * stubbed.
   */
  SweepRunner sweeps;

  AccountPrincipal actor;
  MockMvc http;
  final MockEnvironment environment =
      new MockEnvironment().withProperty("app.deployment-environment", "dev");
  PlatformReviewService service;

  @BeforeEach
  void setup() {
    var author = mock(AuthorPreviewLocalService.class);
    when(author.allowed(owner)).thenReturn(true);
    var mapper = JsonMapper.builder().build();
    service = PlatformReviewFixtures.service(root, author, mapper, "reviewer@example.test");
    sweeps =
        spy(
            new SweepRunner(
                service,
                null,
                null,
                null,
                new MaintenanceJobBeanRegistry(),
                Clock.systemUTC(),
                "dev",
                environment,
                new SweepRequestValidator()));
    http =
        MockMvcBuilders.standaloneSetup(new SweepController(sweeps))
            .setControllerAdvice(new AccountErrorHandler())
            .setCustomArgumentResolvers(
                new HandlerMethodArgumentResolver() {
                  public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == AccountPrincipal.class;
                  }

                  public Object resolveArgument(
                      MethodParameter p,
                      ModelAndViewContainer c,
                      NativeWebRequest r,
                      WebDataBinderFactory b) {
                    return actor;
                  }
                })
            .build();
  }

  private static MockHttpServletRequestBuilder run(String body) {
    return post(RUN).contentType("application/json").content(body);
  }

  @Test
  void theOwnerRunsEachJobAndReadsItsCounts() throws Exception {
    actor = owner;
    doReturn(new SweepRunner.Result("all", "2026-11-06T18:30:00Z", 2, true, "12", 1))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.ALL);
    http.perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("data.job").value("all"))
        .andExpect(jsonPath("data.ranAt").value("2026-11-06T18:30:00Z"))
        .andExpect(jsonPath("data.supportRequestsClosed").value(2))
        .andExpect(jsonPath("data.inboxMerged").value(true))
        .andExpect(jsonPath("data.inboxResult").value("12"))
        .andExpect(jsonPath("data.admissionsRemoved").value(1));
    doReturn(
            new SweepRunner.Result("support-requests", "2026-11-06T18:31:00Z", 0, null, null, null))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.SUPPORT_REQUESTS);
    http.perform(run("{\"job\":\"support-requests\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.job").value("support-requests"))
        .andExpect(jsonPath("data.supportRequestsClosed").value(0))
        .andExpect(jsonPath("data.inboxMerged").value(nullValue()))
        .andExpect(jsonPath("data.inboxResult").value(nullValue()))
        .andExpect(jsonPath("data.admissionsRemoved").value(nullValue()));
    doReturn(
            new SweepRunner.Result(
                "delivery-inbox", "2026-11-06T18:32:00Z", null, false, "no inbox file", null))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.DELIVERY_INBOX);
    http.perform(run("{\"job\":\"delivery-inbox\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.job").value("delivery-inbox"))
        .andExpect(jsonPath("data.supportRequestsClosed").value(nullValue()))
        .andExpect(jsonPath("data.inboxMerged").value(false))
        .andExpect(jsonPath("data.inboxResult").value("no inbox file"));
    doReturn(new SweepRunner.Result("admissions", "2026-11-06T18:33:00Z", null, null, null, 3))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.ADMISSIONS);
    http.perform(run("{\"job\":\"admissions\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.job").value("admissions"))
        .andExpect(jsonPath("data.admissionsRemoved").value(3));
    // A local Domain has no admission cleanup: the job answers 409 and the page says so.
    doThrow(
            new AccountFailure(
                409,
                "SWEEP_NOT_AVAILABLE",
                "The admission cleanup runs only in cloud mode; this Domain has none."))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.ADMISSIONS);
    http.perform(run("{\"job\":\"admissions\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("code").value("SWEEP_NOT_AVAILABLE"))
        .andExpect(header().stringValues("Cache-Control", "no-store"));
    verify(sweeps, times(5)).runNow(eq(owner.accountId()), any());
  }

  @Test
  void anUnknownJobOrAnyOtherBodyIs400BeforeAnySweepRuns() throws Exception {
    actor = owner;
    for (String body :
        new String[] {
          "{\"job\":\"everything\"}",
          "{\"job\":\"ALL\"}",
          "{\"job\":\"\"}",
          "{\"job\":1}",
          "{}",
          "{\"job\":\"all\",\"force\":true}",
          "{\"jobs\":\"all\"}"
        })
      http.perform(run(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("code").value("SWEEP_JOB_INVALID"));
    http.perform(post(RUN)).andExpect(status().is4xxClientError());
    verify(sweeps, never()).runNow(any(UUID.class), any());
  }

  @Test
  void anAdminAReviewerAndAnonymousAreRefusedBeforeAnySweepRuns() throws Exception {
    var access = mock(StaffAccessService.class);
    when(access.permissions(admin)).thenReturn(StaffPermissions.forRoles(false, Set.of("admin")));
    when(access.permissions(reviewer))
        .thenReturn(StaffPermissions.forRoles(false, Set.of("reviewer")));
    when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true, Set.of()));
    service.configureStaffAccess(access);
    actor = admin;
    http.perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("code").value("PLATFORM_REVIEW_ACCESS_DENIED"));
    actor = reviewer;
    http.perform(run("{\"job\":\"support-requests\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("code").value("PLATFORM_REVIEW_ACCESS_DENIED"));
    actor = null;
    http.perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
    verify(sweeps, never()).runNow(any(UUID.class), any());
    // With staff access configured the owner is the account that manages access, as for a triage.
    actor = owner;
    doReturn(new SweepRunner.Result("all", "2026-11-06T18:30:00Z", 0, false, "no inbox file", 0))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.ALL);
    http.perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.admissionsRemoved").value(0));
  }

  @Test
  void prodHasNoManualRun() throws Exception {
    actor = owner;
    environment.setProperty("app.deployment-environment", "prod");
    http.perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("code").value("DEV_ONLY"));
    verify(sweeps, never()).runNow(any(UUID.class), any());
  }

  /**
   * DLV-1016: the three refusals moved from this controller into SweepRunner.requested; their order
   * is the route's contract.
   */
  @Test
  void whenSeveralRefusalsApplyTheGateComesFirstThenTheOwnerCheckThenTheBody() throws Exception {
    String badBody = "{\"job\":\"everything\"}";
    // Anonymous, a bad body, in prod: the DEV-only gate answers.
    actor = null;
    environment.setProperty("app.deployment-environment", "prod");
    http.perform(run(badBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("code").value("DEV_ONLY"));
    // The same request on DEV: the owner check answers, for anonymous and for a signed-in account
    // that is not the owner.
    environment.setProperty("app.deployment-environment", "dev");
    http.perform(run(badBody))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
    actor = reviewer;
    http.perform(run(badBody))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("code").value("PLATFORM_REVIEW_ACCESS_DENIED"));
    // Only the owner is told what is wrong with the body.
    actor = owner;
    http.perform(run(badBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("code").value("SWEEP_JOB_INVALID"));
    verify(sweeps, never()).runNow(any(UUID.class), any());
    verify(sweeps, never()).sweep(any());
  }

  /**
   * DLV-1016: the controller sets Cache-Control once the three checks have passed and before the
   * job runs, as it always did. A failure nobody expected in the run is answered by
   * GlobalExceptionHandler, which adds no header of its own, so the 500 carries the header only
   * because it was set first; an expected refusal gets it from AccountErrorHandler.
   */
  @Test
  void anUnexpectedFailureOfTheRunIsAnsweredWithTheHeaderSetBeforeIt() throws Exception {
    var withBothHandlers =
        MockMvcBuilders.standaloneSetup(new SweepController(sweeps))
            .setControllerAdvice(new AccountErrorHandler(), new GlobalExceptionHandler())
            .setCustomArgumentResolvers(
                new HandlerMethodArgumentResolver() {
                  public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == AccountPrincipal.class;
                  }

                  public Object resolveArgument(
                      MethodParameter p,
                      ModelAndViewContainer c,
                      NativeWebRequest r,
                      WebDataBinderFactory b) {
                    return actor;
                  }
                })
            .build();
    actor = owner;
    doThrow(new IllegalStateException("synthetic failure of the run"))
        .when(sweeps)
        .runNow(owner.accountId(), SweepJob.ALL);
    withBothHandlers
        .perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().stringValues("Cache-Control", "no-store"));
    // The same failure before the checks have passed (here: of the owner check) is answered without
    // it.
    actor = reviewer;
    doThrow(new IllegalStateException("synthetic failure of the check"))
        .when(sweeps)
        .requested(eq(reviewer), any());
    withBothHandlers
        .perform(run("{\"job\":\"all\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().doesNotExist("Cache-Control"));
  }
}
