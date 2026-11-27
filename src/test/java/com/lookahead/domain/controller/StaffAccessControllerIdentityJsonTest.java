package com.lookahead.domain.controller;

import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.access.StaffAccess.Assignment;
import com.lookahead.domain.db.store.access.StaffAlias;
import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.handler.GlobalExceptionHandler;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountDirectoryService;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.access.StaffAliasService;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * DLV-969 (owner decision 2026-11-03), pinned for DLV-1016: the exact bytes {@code GET /api/v1/admin/access} answers.
 * Only the owner sees who people are; everyone else sees a permanent role label and never an account id or an
 * invitation email; everyone sees themselves. The real controller, the real {@link StaffAccessService} and
 * {@link StaffAliasService} and the JSON mapper Spring Boot configures; only the stores are stubbed. Nothing in this
 * test names the class that applies the rule, so it holds wherever the rule lives.
 */
class StaffAccessControllerIdentityJsonTest {
    static final UUID OWNER_ID = id("01"), COAUTHOR_ID = id("02"), PERSON_ID = id("03");
    static final AccountPrincipal OWNER = new AccountPrincipal(OWNER_ID, "owner@example.invalid", "Owner Person", true, true);
    static final AccountPrincipal COAUTHOR = new AccountPrincipal(COAUTHOR_ID, "coauthor@example.invalid", "Co Author", true, false);
    static final AccountPrincipal UNNAMED_COAUTHOR = new AccountPrincipal(COAUTHOR_ID, "coauthor@example.invalid", " ", true, false);
    static final List<Assignment> ROWS = List.of(
            new Assignment(id("a1"), OWNER_ID, null, Set.of(), Set.of(), false, 5),
            new Assignment(id("a2"), COAUTHOR_ID, null, Set.of("co-author"), Set.of("learn:java"), false, 2),
            new Assignment(id("a3"), PERSON_ID, null, new TreeSet<>(List.of("admin", "tester")), new TreeSet<>(List.of("grow:system-design", "learn:java")), true, 3),
            new Assignment(id("a4"), null, "invitee@example.invalid", Set.of("tester"), Set.of(), true, 1));

    static final String OWNER_SEES = "["
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a1\",\"accountId\":\"00000000-0000-4000-8000-000000000001\",\"pendingEmail\":null,\"roles\":[],\"topicGrants\":[],\"fullCurriculum\":false,\"revision\":5},"
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a2\",\"accountId\":\"00000000-0000-4000-8000-000000000002\",\"pendingEmail\":null,\"roles\":[\"co-author\"],\"topicGrants\":[\"learn:java\"],\"fullCurriculum\":false,\"revision\":2},"
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a3\",\"accountId\":\"00000000-0000-4000-8000-000000000003\",\"pendingEmail\":null,\"roles\":[\"admin\",\"tester\"],\"topicGrants\":[\"grow:system-design\",\"learn:java\"],\"fullCurriculum\":true,\"revision\":3},"
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a4\",\"accountId\":null,\"pendingEmail\":\"invitee@example.invalid\",\"roles\":[\"tester\"],\"topicGrants\":[],\"fullCurriculum\":true,\"revision\":1}]";
    /** The owner's row is not listed for a co-author; the other person is "Admin 4"; the invitation has no email. */
    static final String OTHERS_AS_LABELS =
              "{\"id\":\"00000000-0000-4000-8000-0000000000a3\",\"accountId\":null,\"pendingEmail\":null,\"label\":\"Admin 4\",\"pending\":false,\"roles\":[\"admin\",\"tester\"],\"topicGrants\":[\"grow:system-design\",\"learn:java\"],\"fullCurriculum\":true,\"revision\":3},"
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a4\",\"accountId\":null,\"pendingEmail\":null,\"label\":\"Pending invitation\",\"pending\":true,\"roles\":[\"tester\"],\"topicGrants\":[],\"fullCurriculum\":true,\"revision\":1}]";
    static final String COAUTHOR_SEES = "["
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a2\",\"accountId\":\"00000000-0000-4000-8000-000000000002\",\"pendingEmail\":null,\"label\":\"Co Author\",\"pending\":false,\"roles\":[\"co-author\"],\"topicGrants\":[\"learn:java\"],\"fullCurriculum\":false,\"revision\":2},"
            + OTHERS_AS_LABELS;
    /** With no display name the caller's own row carries their username. */
    static final String UNNAMED_COAUTHOR_SEES = "["
            + "{\"id\":\"00000000-0000-4000-8000-0000000000a2\",\"accountId\":\"00000000-0000-4000-8000-000000000002\",\"pendingEmail\":null,\"label\":\"coauthor@example.invalid\",\"pending\":false,\"roles\":[\"co-author\"],\"topicGrants\":[\"learn:java\"],\"fullCurriculum\":false,\"revision\":2},"
            + OTHERS_AS_LABELS;

    final StaffAccess repository = mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final StaffAlias aliasStore = mock(StaffAlias.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final StaffAccessService access = new StaffAccessService(repository,
            new AuthorPreviewLocalService(new MockEnvironment().withProperty("app.deployment-environment", "dev")),
            null); // the topic catalog: not read by the list
    final StaffAliasService aliases = new StaffAliasService(aliasStore, access, repository);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StaffAccessController(access,
                    mock(AccountDirectoryService.class, withSettings().mockMaker(MockMakers.SUBCLASS)), aliases))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(TestAccountControllerTest.bootJsonMapper()))
            .setControllerAdvice(new AccountErrorHandler(), new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

    StaffAccessControllerIdentityJsonTest() {
        when(repository.list()).thenReturn(ROWS);
        when(repository.owners()).thenReturn(Set.of(OWNER_ID));
        for (Assignment row : ROWS) if (row.accountId() != null) when(repository.forAccount(row.accountId())).thenReturn(row);
        when(aliasStore.owner(OWNER_ID)).thenReturn(true);
        when(aliasStore.number(PERSON_ID, "admin")).thenReturn(4);
    }
    @AfterEach void signOut() { SecurityContextHolder.clearContext(); }

    @Test void theOwnerSeesEveryRowWithItsAccountIdAndInvitationEmail() throws Exception {
        assertThat(listAs(OWNER)).isEqualTo(OWNER_SEES);
        verifyNoInteractions(aliasStore);
    }
    @Test void aCoAuthorSeesRoleLabelsAndNeverAnotherAccountIdOrAnInvitationEmail() throws Exception {
        String body = listAs(COAUTHOR);
        assertThat(body).isEqualTo(COAUTHOR_SEES);
        assertThat(body).doesNotContain(OWNER_ID.toString(), PERSON_ID.toString(), "invitee@example.invalid", "Owner Person");
    }
    @Test void aCallerSeesTheirOwnRowWithTheirOwnAccountIdAndName() throws Exception {
        assertThat(listAs(COAUTHOR)).startsWith("[{\"id\":\"00000000-0000-4000-8000-0000000000a2\",\"accountId\":\"" + COAUTHOR_ID + "\",\"pendingEmail\":null,\"label\":\"Co Author\",");
        assertThat(listAs(UNNAMED_COAUTHOR)).isEqualTo(UNNAMED_COAUTHOR_SEES);
        // Their own label is their own name, never a number taken from the alias store.
        verify(aliasStore, never()).number(eq(COAUTHOR_ID), any());
    }
    /** An admin or a learner may not open the list at all: the refusal carries no row. */
    @Test void anyoneWhoIsNotTheOwnerOrACoAuthorIsRefusedBeforeAnyRowIsRead() throws Exception {
        var admin = new AccountPrincipal(PERSON_ID, "admin@example.invalid", "Admin Person", true, false);
        signIn(admin);
        var response = mvc.perform(get("/api/v1/admin/access")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"ACCESS_READER_REQUIRED\"").doesNotContain("00000000-0000-4000-8000-0000000000a");
        verify(repository, never()).list();
    }

    /** The {@code data} value of the answer, byte for byte; the envelope around it is {@code {"data":…,"timestamp":"…"}}. */
    private String listAs(AccountPrincipal caller) throws Exception {
        signIn(caller);
        var response = mvc.perform(get("/api/v1/admin/access")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String body = response.getContentAsString();
        assertThat(body).startsWith("{\"data\":").matches("(?s).*,\"timestamp\":\"[0-9TZ:.-]+\"}");
        return body.substring("{\"data\":".length(), body.lastIndexOf(",\"timestamp\":\""));
    }
    private static void signIn(AccountPrincipal caller) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(caller, "unused"));
    }
    private static UUID id(String last) { return UUID.fromString("00000000-0000-4000-8000-0000000000" + last); }
}
