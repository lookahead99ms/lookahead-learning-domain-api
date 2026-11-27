package com.lookahead.domain.controller;

import com.lookahead.domain.db.store.review.PlatformReviewStatusHistoryMemoryStore;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.service.review.PlatformReviewFixtures;
import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DLV-971: the Gateway-facing JSON of /api/v1/delivery/plan and its statuses. */
class DeliveryPlanControllerTest {
    static final String ROOT = "/api/v1/delivery/plan";
    static final String ITEM = "{\"title\":\"New work\",\"summary\":\"Why\",\"type\":\"story\",\"statusId\":\"backlog\",\"priorityId\":\"P2\",\"stageId\":\"stage-1\",\"labels\":[]}";
    @TempDir Path root;
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner@lookahead.test", "Owner", true);
    final AccountPrincipal learner = new AccountPrincipal(UUID.randomUUID(), "learner@example.test", "Learner", true);
    AccountPrincipal actor;
    MockMvc http;
    StaffAccessService access;
    final MockEnvironment environment = new MockEnvironment().withProperty("app.deployment-environment", "dev");

    @BeforeEach void setup() throws Exception {
        actor = owner;
        access = mock(StaffAccessService.class);
        when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true, Set.of()));
        when(access.permissions(learner)).thenReturn(StaffPermissions.forRoles(false, Set.of()));
        PlatformReviewFixtures.seedPlan(root);
        var plans = PlatformReviewFixtures.delivery(root, JsonMapper.builder().build(), access, new PlatformReviewStatusHistoryMemoryStore(), Clock.systemUTC(), "local", environment);
        http = MockMvcBuilders.standaloneSetup(new DeliveryPlanController(plans)).setControllerAdvice(new AccountErrorHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == AccountPrincipal.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory b) { return actor; }
                }).build();
    }

    @Test void theOwnerOrACoAuthorUploadsThePlanFileWhichIsMergedAtOnceAndSyncsTheInbox() throws Exception {
        var coauthor = new AccountPrincipal(UUID.randomUUID(), "coauthor@example.test", "Co-author", true);
        var admin = new AccountPrincipal(UUID.randomUUID(), "admin@example.test", "Admin", true);
        when(access.permissions(coauthor)).thenReturn(StaffPermissions.forRoles(false, Set.of("co-author")));
        when(access.permissions(admin)).thenReturn(StaffPermissions.forRoles(false, Set.of("admin")));
        String local = PlatformReviewFixtures.PLAN.replace("\"version\": 7", "\"version\": 7, \"baseVersion\": 7").replace("First ticket", "Renamed locally");
        Assertions.assertThat(local).contains("\"baseVersion\": 7");
        http.perform(put(ROOT + "/inbox").contentType("application/json").content(local))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.synced").value(true)).andExpect(jsonPath("data.version").value(8))
                .andExpect(jsonPath("data.counts.takenLocal").value(1)).andExpect(jsonPath("data.reason").value(Matchers.nullValue()))
                // DLV-1007: the version the merge started from and the ticket ids behind each count.
                .andExpect(jsonPath("data.versionBefore").value(7)).andExpect(jsonPath("data.details.takenLocal[0]").value("DLV-1"))
                .andExpect(jsonPath("data.details.conflicts").isEmpty());
        Assertions.assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isFalse();
        Assertions.assertThat(Files.exists(root.resolve("delivery/archive/plan-v7.json"))).isTrue();
        http.perform(get(ROOT)).andExpect(jsonPath("data.version").value(8)).andExpect(jsonPath("data.workItems[0].title").value("Renamed locally"));
        // Nothing in the inbox: Sync plan inbox says so and the version stays.
        http.perform(post(ROOT + "/inbox/sync")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.synced").value(false)).andExpect(jsonPath("data.version").value(8)).andExpect(jsonPath("data.reason").value("no inbox file"));
        // A file placed in the inbox by hand is merged by Sync plan inbox.
        Files.createDirectories(root.resolve("delivery/inbox"));
        Files.writeString(root.resolve("delivery/inbox/plan.json"), local.replace("\"baseVersion\": 7", "\"baseVersion\": 8").replace("Renamed locally", "Renamed again"));
        http.perform(post(ROOT + "/inbox/sync")).andExpect(jsonPath("data.synced").value(true)).andExpect(jsonPath("data.version").value(9));
        // Not a plan, or a base DEV never had: refused before anything is written.
        http.perform(put(ROOT + "/inbox").contentType("application/json").content("not json"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("DELIVERY_INBOX_INVALID"));
        http.perform(put(ROOT + "/inbox").contentType("application/json").content("{\"version\": 1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("DELIVERY_INBOX_INVALID"));
        http.perform(put(ROOT + "/inbox").contentType("application/json").content(local.replace("\"baseVersion\": 7", "\"baseVersion\": 3")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("DELIVERY_INBOX_BASE_UNKNOWN"));
        Assertions.assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isFalse();
        // A co-author may; an admin and a learner may not.
        actor = coauthor;
        http.perform(post(ROOT + "/inbox/sync")).andExpect(status().isOk()).andExpect(jsonPath("data.synced").value(false));
        actor = admin;
        http.perform(put(ROOT + "/inbox").contentType("application/json").content(local)).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("DELIVERY_ACCESS_DENIED"));
        http.perform(post(ROOT + "/inbox/sync")).andExpect(status().isForbidden());
        actor = learner;
        http.perform(put(ROOT + "/inbox").contentType("application/json").content(local)).andExpect(status().isForbidden());
        // PROD has neither route (owner, 2026-11-07): 404 DEV_ONLY before any check, for the owner too.
        actor = owner;
        environment.setProperty("app.deployment-environment", "prod");
        http.perform(put(ROOT + "/inbox").contentType("application/json").content(local)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("DEV_ONLY"));
        http.perform(post(ROOT + "/inbox/sync")).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("DEV_ONLY"));
        http.perform(get(ROOT)).andExpect(status().isOk());
        http.perform(post(ROOT + "/work-items").contentType("application/json").content("{\"expectedVersion\":9,\"item\":" + ITEM + "}")).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("DEV_ONLY"));
        http.perform(put(ROOT + "/work-items/DLV-1").contentType("application/json").content("{\"expectedVersion\":9,\"item\":" + ITEM + "}")).andExpect(status().isNotFound());
        http.perform(post(ROOT + "/work-items/DLV-1/resolve-conflict").contentType("application/json").content("{\"expectedVersion\":9,\"keep\":\"dev\"}")).andExpect(status().isNotFound());
    }

    @Test void readCreateUpdateAndResolveWithTheirStatuses() throws Exception {
        http.perform(get(ROOT)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.version").value(7)).andExpect(jsonPath("data.workItems[0].id").value("DLV-1"))
                .andExpect(jsonPath("data.workItems[0].createdIn").value("local")).andExpect(jsonPath("data.workItems[0].origin").value(Matchers.nullValue()))
                .andExpect(jsonPath("data.workItems[0].pendingLocal").value(Matchers.nullValue()));
        http.perform(post(ROOT + "/work-items").contentType("application/json").content("{\"expectedVersion\":7,\"item\":" + ITEM + "}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.version").value(8)).andExpect(jsonPath("data.item.id").value("DLV-2"))
                .andExpect(jsonPath("data.item.createdIn").value("local")).andExpect(jsonPath("data.item.updatedAt").isString());
        http.perform(post(ROOT + "/work-items").contentType("application/json").content("{\"expectedVersion\":7,\"item\":" + ITEM + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("VERSION_CONFLICT")).andExpect(jsonPath("currentRevision").value(8));
        http.perform(post(ROOT + "/work-items").contentType("application/json").content("{\"item\":" + ITEM + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("DELIVERY_ITEM_INVALID"));
        http.perform(post(ROOT + "/work-items").contentType("application/json").content("{\"expectedVersion\":\"8\",\"item\":" + ITEM + "}"))
                .andExpect(status().isUnprocessableEntity());
        http.perform(put(ROOT + "/work-items/DLV-2").contentType("application/json").content("{\"expectedVersion\":8,\"item\":" + ITEM.replace("New work", "Renamed") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.version").value(9)).andExpect(jsonPath("data.item.title").value("Renamed"));
        http.perform(put(ROOT + "/work-items/DLV-404").contentType("application/json").content("{\"expectedVersion\":9,\"item\":" + ITEM + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("DELIVERY_ITEM_NOT_FOUND"));
        http.perform(post(ROOT + "/work-items/DLV-2/resolve-conflict").contentType("application/json").content("{\"expectedVersion\":9,\"keep\":\"dev\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DELIVERY_NO_CONFLICT"));
        http.perform(post(ROOT + "/work-items/DLV-2/resolve-conflict").contentType("application/json").content("{\"expectedVersion\":9,\"keep\":\"dev\",\"extra\":1}"))
                .andExpect(status().isUnprocessableEntity());
        http.perform(post(ROOT + "/work-items/DLV-2/resolve-conflict").contentType("application/json").content("{\"expectedVersion\":9,\"keep\":7}"))
                .andExpect(status().isUnprocessableEntity());
        actor = learner;
        http.perform(get(ROOT)).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("DELIVERY_ACCESS_DENIED"));
    }

    @Test void aMissingPlanIsNotFoundWithAClearMessage() throws Exception {
        Files.delete(root.resolve("delivery/plan.json"));
        http.perform(get(ROOT)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("DELIVERY_PLAN_MISSING"))
                .andExpect(jsonPath("message").value(Matchers.containsString("delivery/plan.json")));
    }

    /**
     * DLV-1016: the DEV-only gate and the checks on the body moved from this controller into DeliveryPlanService. A write
     * still meets the gate first, then the check of its body, then the access check of the method that does the work.
     */
    @Test void aWriteMeetsTheGateFirstThenTheBodyCheckThenTheAccessCheck() throws Exception {
        String noVersion = "{\"item\":" + ITEM + "}", badKeep = "{\"expectedVersion\":\"nine\",\"keep\":7}", good = "{\"expectedVersion\":9,\"item\":" + ITEM + "}";
        actor = learner;
        // In prod a malformed body from an account without access is 404 DEV_ONLY on every write route, with the one header of a refusal.
        environment.setProperty("app.deployment-environment", "prod");
        for (var request : List.of(post(ROOT + "/work-items").contentType("application/json").content(noVersion),
                put(ROOT + "/work-items/DLV-1").contentType("application/json").content(noVersion),
                post(ROOT + "/work-items/DLV-1/resolve-conflict").contentType("application/json").content(badKeep),
                put(ROOT + "/inbox").contentType("application/json").content("[]"), post(ROOT + "/inbox/sync")))
            http.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("DEV_ONLY")).andExpect(header().stringValues("Cache-Control", "no-store"));
        // On DEV the same account is told what is wrong with the body (422) before it is told that it has no access (403).
        environment.setProperty("app.deployment-environment", "dev");
        http.perform(post(ROOT + "/work-items").contentType("application/json").content(noVersion)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("DELIVERY_ITEM_INVALID")).andExpect(jsonPath("message").value("Send the plan version you loaded as expectedVersion."));
        http.perform(put(ROOT + "/work-items/DLV-1").contentType("application/json").content(noVersion)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("message").value("Send the plan version you loaded as expectedVersion."));
        // A conflict resolution: keep is checked before expectedVersion.
        http.perform(post(ROOT + "/work-items/DLV-1/resolve-conflict").contentType("application/json").content(badKeep)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("message").value("Choose dev or local."));
        http.perform(post(ROOT + "/work-items/DLV-1/resolve-conflict").contentType("application/json").content("{\"expectedVersion\":\"nine\",\"keep\":\"dev\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("message").value("expectedVersion must be the plan's version number."));
        // With a well-formed body the access check of the working method answers (a create and a conflict resolution check it first).
        http.perform(post(ROOT + "/work-items").contentType("application/json").content(good)).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("DELIVERY_ACCESS_DENIED"));
        http.perform(post(ROOT + "/work-items/DLV-1/resolve-conflict").contentType("application/json").content("{\"expectedVersion\":9,\"keep\":\"dev\"}")).andExpect(status().isForbidden());
    }
}
