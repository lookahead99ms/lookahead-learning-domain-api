package com.lookahead.domain.delivery;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StaffPermissions;
import com.lookahead.domain.review.platform.PlatformReviewFixtures;
import com.lookahead.domain.review.platform.ReviewStatusHistory;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
    final org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment", "dev");

    @BeforeEach void setup() throws Exception {
        actor = owner;
        access = mock(StaffAccessService.class);
        when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true, Set.of()));
        when(access.permissions(learner)).thenReturn(StaffPermissions.forRoles(false, Set.of()));
        PlatformReviewFixtures.seedPlan(root);
        var plans = PlatformReviewFixtures.delivery(root, JsonMapper.builder().build(), access, new ReviewStatusHistory.InMemory(), Clock.systemUTC(), "local");
        http = MockMvcBuilders.standaloneSetup(new DeliveryPlanController(plans, environment)).setControllerAdvice(new AccountErrorHandler())
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
        org.assertj.core.api.Assertions.assertThat(local).contains("\"baseVersion\": 7");
        http.perform(put(ROOT + "/inbox").contentType("application/json").content(local))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.synced").value(true)).andExpect(jsonPath("data.version").value(8))
                .andExpect(jsonPath("data.counts.takenLocal").value(1)).andExpect(jsonPath("data.reason").value(org.hamcrest.Matchers.nullValue()));
        org.assertj.core.api.Assertions.assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(Files.exists(root.resolve("delivery/archive/plan-v7.json"))).isTrue();
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
        org.assertj.core.api.Assertions.assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isFalse();
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
                .andExpect(jsonPath("data.workItems[0].createdIn").value("local")).andExpect(jsonPath("data.workItems[0].origin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("data.workItems[0].pendingLocal").value(org.hamcrest.Matchers.nullValue()));
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
                .andExpect(jsonPath("message").value(org.hamcrest.Matchers.containsString("delivery/plan.json")));
    }
}
