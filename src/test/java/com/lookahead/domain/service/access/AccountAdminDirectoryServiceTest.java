package com.lookahead.domain.service.access;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.controller.AccountAdminDirectoryController;
import com.lookahead.domain.db.store.access.AccountAdminDirectory;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.membership.MembershipAccessSummaryService;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import com.lookahead.domain.util.ParallelRows;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountAdminDirectoryServiceTest {
  @Test
  void deniesBeforeEnumeration() {
    var directory = mock(AccountDirectoryService.class);
    var access = mock(StaffAccessService.class);
    var actor = new AccountPrincipal(UUID.randomUUID(), "admin", "Admin", true);
    doThrow(new AccountFailure(403, "OWNER_REQUIRED", "Owner required"))
        .when(access)
        .requireOwner(actor);
    var service =
        new AccountAdminDirectoryService(
            directory,
            access,
            mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS)),
            mock(MembershipAccessSummaryService.class),
            mock(AccountAdminDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS)),
            mock(TestAccountModeService.class),
            mock(AccountRemovalService.class));
    assertThatThrownBy(() -> service.list(actor, "", 0)).isInstanceOf(AccountFailure.class);
    verifyNoInteractions(directory);
  }

  @Test
  void joinsIdentityWithCurrentDomainRolesAndAccessWithoutWrites() {
    var directory = mock(AccountDirectoryService.class);
    var access = mock(StaffAccessService.class);
    var staff = mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var summaries = mock(MembershipAccessSummaryService.class);
    var jdbc = mock(AccountAdminDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var localPolicy = mock(TestAccountModeService.class);
    when(localPolicy.local()).thenReturn(true);
    var actor = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);
    UUID real = UUID.randomUUID(), admin = UUID.randomUUID();
    when(directory.list("", 0))
        .thenReturn(
            new AccountDirectoryModels.Page(
                List.of(
                    new AccountDirectoryModels.Account(actor.accountId(), "owner", "Owner", null),
                    new AccountDirectoryModels.Account(real, "real", "Real", "real@example.test"),
                    new AccountDirectoryModels.Account(admin, "admin", "Admin", null)),
                0,
                false));
    when(staff.forAccount(admin))
        .thenReturn(
            new StaffAccess.Assignment(
                UUID.randomUUID(), admin, null, Set.of("admin"), Set.of(), false, 1));
    when(jdbc.testAccount(admin)).thenReturn(true);
    var summary =
        new MembershipAccessSummaryService.Summary(
            "Regular · Learn", List.of("Regular · Learn"), List.of());
    when(summaries.get(actor, real)).thenReturn(summary);
    when(summaries.get(actor, actor.accountId()))
        .thenReturn(new MembershipAccessSummaryService.Summary("Free", List.of("Free"), List.of()));
    when(summaries.get(actor, admin))
        .thenReturn(new MembershipAccessSummaryService.Summary("Free", List.of("Free"), List.of()));
    var removal = mock(AccountRemovalService.class);
    when(removal.options(any(), any(), anyBoolean()))
        .thenReturn(AccountRemovalService.Options.NONE);
    when(removal.options(actor.accountId(), real, false))
        .thenReturn(new AccountRemovalService.Options("disabled", false, true, true));
    var result =
        new AccountAdminDirectoryService(
                directory, access, staff, summaries, jdbc, localPolicy, removal)
            .list(actor, "", 0);
    assertThat(result.accounts()).hasSize(3);
    assertThat(result.accounts().get(0).roles()).containsExactly("owner");
    assertThat(result.accounts().get(0).fullCurriculum()).isTrue();
    assertThat(result.accounts().get(1).roles()).containsExactly("user");
    assertThat(result.accounts().get(1).access()).isEqualTo(summary);
    assertThat(result.accounts().get(1).testAccount()).isFalse();
    assertThat(result.accounts().get(2).testAccount()).isTrue();
    assertThat(result.accounts().get(1).canDowngrade()).isTrue();
    assertThat(result.accounts().get(1).canDelete()).isFalse();
    assertThat(result.accounts().get(2).canDowngrade()).isFalse();
    assertThat(result.accounts().get(2).canDelete()).isFalse();
    assertThat(result.accounts().get(2).fullCurriculum()).isTrue();
    // DLV-969: the removal options come from the removal service; the owner's own row never asks
    // for them.
    var realRow = result.accounts().get(1);
    assertThat(
            List.of(
                String.valueOf(realRow.signIn()),
                realRow.canDisable(),
                realRow.canEnable(),
                realRow.canRemove()))
        .containsExactly("disabled", false, true, true);
    var ownRow = result.accounts().get(0);
    assertThat(
            List.of(
                String.valueOf(ownRow.signIn()),
                ownRow.canDisable(),
                ownRow.canEnable(),
                ownRow.canRemove()))
        .containsExactly("null", false, false, false);
    verify(removal, never()).options(eq(actor.accountId()), eq(actor.accountId()), anyBoolean());
    // DLV-969 S36: the owner sees how many other accounts share a row's verified email; no email,
    // no query.
    assertThat(realRow.sameEmailAccounts()).isZero();
    when(jdbc.sameEmailAccounts(real, "real@example.test")).thenReturn(2);
    var again =
        new AccountAdminDirectoryService(
                directory, access, staff, summaries, jdbc, localPolicy, removal)
            .list(actor, "", 0);
    assertThat(again.accounts().get(1).sameEmailAccounts()).isEqualTo(2);
    assertThat(again.accounts().get(0).sameEmailAccounts()).isZero();
    assertThat(again.accounts().get(2).sameEmailAccounts()).isZero();
    // The owner sees every sign-in method of a row, from cloud_identities; a test account's row
    // asks nothing.
    assertThat(again.accounts().get(1).signInMethods()).isEmpty();
    when(jdbc.signInMethods(real))
        .thenReturn(
            List.of(
                Map.of("provider", "google", "email", "real@example.test"),
                Map.of("provider", "cognito")));
    var third =
        new AccountAdminDirectoryService(
                directory, access, staff, summaries, jdbc, localPolicy, removal)
            .list(actor, "", 0);
    assertThat(third.accounts().get(1).signInMethods())
        .containsExactly(
            Map.of("provider", "google", "email", "real@example.test"),
            Map.of("provider", "cognito"));
    verify(jdbc, never()).signInMethods(admin);
    // DLV-969 (owner, 2026-11-05): the owner sees each person's saved GitHub link; none is null; a
    // test account asks nothing.
    assertThat(third.accounts().get(1).github()).isNull();
    when(jdbc.github(real)).thenReturn("https://github.com/real-person");
    var fourth =
        new AccountAdminDirectoryService(
                directory, access, staff, summaries, jdbc, localPolicy, removal)
            .list(actor, "", 0);
    assertThat(fourth.accounts().get(1).github()).isEqualTo("https://github.com/real-person");
    assertThat(fourth.accounts().get(2).github()).isNull();
    verify(jdbc, never()).github(admin);
  }

  @Test
  void aNonOwnerNeverReachesAnyGithubLink() {
    var directory = mock(AccountDirectoryService.class);
    var access = mock(StaffAccessService.class);
    var jdbc = mock(AccountAdminDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var actor = new AccountPrincipal(UUID.randomUUID(), "coauthor", "Co-author", true);
    doThrow(new AccountFailure(403, "OWNER_REQUIRED", "Owner required"))
        .when(access)
        .requireOwner(actor);
    var service =
        new AccountAdminDirectoryService(
            directory,
            access,
            mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS)),
            mock(MembershipAccessSummaryService.class),
            jdbc,
            mock(TestAccountModeService.class),
            mock(AccountRemovalService.class));
    assertThatThrownBy(() -> service.list(actor, "", 0)).isInstanceOf(AccountFailure.class);
    verifyNoInteractions(directory, jdbc);
  }

  @Test
  void controllerDisablesCaching() {
    var service = mock(AccountAdminDirectoryService.class);
    var response = new MockHttpServletResponse();
    new AccountAdminDirectoryController(service).list(null, "", 2, response);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    verify(service).list(null, "", 2);
  }

  @Test
  void readsRowsInParallelAndKeepsThePageOrder() throws Exception {
    var directory = mock(AccountDirectoryService.class);
    var access = mock(StaffAccessService.class);
    var staff = mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var summaries = mock(MembershipAccessSummaryService.class);
    var jdbc = mock(AccountAdminDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var localPolicy = mock(TestAccountModeService.class);
    var removal = mock(AccountRemovalService.class);
    var actor = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);
    var accounts = new ArrayList<AccountDirectoryModels.Account>();
    for (int i = 0; i < 20; i++)
      accounts.add(
          new AccountDirectoryModels.Account(
              UUID.randomUUID(), "user" + i, "User " + i, "user" + i + "@example.test"));
    when(directory.list("", 0)).thenReturn(new AccountDirectoryModels.Page(accounts, 0, false));
    // Count the rows inside the store at once: serial reads would never exceed one.
    var inside = new AtomicInteger();
    var peak = new AtomicInteger();
    when(jdbc.testAccount(any()))
        .thenAnswer(call -> {
          peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
          Thread.sleep(40);
          inside.decrementAndGet();
          return false;
        });
    when(summaries.get(any(), any()))
        .thenReturn(new MembershipAccessSummaryService.Summary("Free", List.of("Free"), List.of()));
    when(removal.options(any(), any(), anyBoolean()))
        .thenReturn(AccountRemovalService.Options.NONE);
    var service =
        new AccountAdminDirectoryService(
            directory, access, staff, summaries, jdbc, localPolicy, removal);
    var result = service.list(actor, "", 0);
    assertThat(result.accounts())
        .extracting(AccountAdminDirectoryService.Row::accountId)
        .containsExactlyElementsOf(
            accounts.stream().map(AccountDirectoryModels.Account::accountId).toList());
    assertThat(peak.get()).isBetween(2, ParallelRows.PARALLELISM);
  }

  @Test
  void aFailedRowReachesTheCallerAsItsOwnException() {
    var directory = mock(AccountDirectoryService.class);
    var access = mock(StaffAccessService.class);
    var staff = mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var summaries = mock(MembershipAccessSummaryService.class);
    var jdbc = mock(AccountAdminDirectory.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var actor = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);
    when(directory.list("", 0))
        .thenReturn(
            new AccountDirectoryModels.Page(
                List.of(new AccountDirectoryModels.Account(UUID.randomUUID(), "a", "A", null)),
                0,
                false));
    when(jdbc.testAccount(any()))
        .thenThrow(new AccountFailure(503, "SERVICE_UNAVAILABLE", "Try again"));
    var service =
        new AccountAdminDirectoryService(
            directory,
            access,
            staff,
            summaries,
            jdbc,
            mock(TestAccountModeService.class),
            mock(AccountRemovalService.class));
    assertThatThrownBy(() -> service.list(actor, "", 0))
        .isInstanceOf(AccountFailure.class)
        .hasMessageContaining("Try again");
  }
}
