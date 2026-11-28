package com.lookahead.domain.service.access;

import com.lookahead.domain.db.store.access.AccountAdminDirectory;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.membership.MembershipAccessSummaryService;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import com.lookahead.domain.util.ParallelRows;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owner-only directory: Identity owns identity fields; Domain owns roles and access. */
@Service
public class AccountAdminDirectoryService {
  /**
   * sameEmailAccounts (DLV-969 S36): other accounts, not deleted or test, with this verified email;
   * signInMethods: each sign-in of the account, oldest first, as {provider, email?}, test
   * identities left out. github (DLV-969, owner 2026-11-05): the GitHub profile link the person
   * saved (cloud_account_profile_links), null when none or for a test account, so the owner can add
   * staff to repositories and workflows. The owner only; never logged.
   */
  public record Row(
      UUID accountId,
      String username,
      String displayName,
      String email,
      Set<String> roles,
      boolean testAccount,
      boolean fullCurriculum,
      MembershipAccessSummaryService.Summary access,
      boolean canDowngrade,
      boolean canDelete,
      String signIn,
      boolean canDisable,
      boolean canEnable,
      boolean canRemove,
      int sameEmailAccounts,
      List<Map<String, Object>> signInMethods,
      String github) {}

  public record Page(List<Row> accounts, int page, boolean hasNext) {}

  private final AccountDirectoryService directory;
  private final StaffAccessService access;
  private final StaffAccess staff;
  private final MembershipAccessSummaryService summaries;
  private final AccountAdminDirectory store;
  private final TestAccountModeService localPolicy;
  private final AccountRemovalService removal;

  /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
  @Autowired
  public AccountAdminDirectoryService(
      AccountDirectoryService directory,
      StaffAccessService access,
      StaffAccess staff,
      MembershipAccessSummaryService summaries,
      AccountAdminDirectory store,
      TestAccountModeService localPolicy,
      AccountRemovalService removal) {
    this.directory = directory;
    this.access = access;
    this.staff = staff;
    this.summaries = summaries;
    this.store = store;
    this.localPolicy = localPolicy;
    this.removal = removal;
  }

  /**
   * Each row is about ten independent reads, so the rows are read side by side ({@link
   * ParallelRows}) and come back in page order. Every read here is a query: no row writes, so a
   * retry or an overlapping request can only repeat a read, never a change.
   */
  public Page list(AccountPrincipal actor, String query, int page) {
    access.requireOwner(actor); // Before any identity request or database enumeration.
    var source = directory.list(query, page);
    return new Page(
        ParallelRows.map(source.accounts(), account -> row(actor, account)),
        source.page(),
        source.hasNext());
  }

  private Row row(AccountPrincipal actor, AccountDirectoryModels.Account account) {
    var assignment = staff.forAccount(account.accountId());
    boolean owner =
        actor.accountId().equals(account.accountId()) || store.author(account.accountId());
    Set<String> roles =
        owner
            ? Set.of("owner")
            : assignment == null || assignment.roles().isEmpty()
                ? Set.of("user")
                : assignment.roles();
    boolean full =
        owner
            || StaffPermissions.forRoles(false, assignment == null ? Set.of() : assignment.roles())
                .fullCurriculum()
            || assignment != null && assignment.fullCurriculum();
    boolean test = store.testAccount(account.accountId());
    var summary = summaries.get(actor, account.accountId());
    boolean purchases = store.openPurchases(account.accountId());
    boolean grants = store.grants(account.accountId());
    boolean privileged =
        !roles.equals(Set.of("user"))
            || assignment != null
                && (assignment.fullCurriculum() || !assignment.topicGrants().isEmpty());
    boolean eligible = !owner && !test;
    // DLV-969: Disable, Enable and Delete for DEV sign-in accounts (never the owner, an author or a
    // test account).
    var options =
        owner
            ? AccountRemovalService.Options.NONE
            : removal.options(actor.accountId(), account.accountId(), test);
    return new Row(
        account.accountId(),
        account.username(),
        account.displayName(),
        account.email(),
        roles,
        test,
        full,
        summary,
        eligible && (privileged || purchases || grants || !"Free".equals(summary.label())),
        eligible
            && localPolicy.local()
            && !privileged
            && !purchases
            && !grants
            && "Free".equals(summary.label()),
        options.signIn(),
        options.canDisable(),
        options.canEnable(),
        options.canRemove(),
        test ? 0 : store.sameEmailAccounts(account.accountId(), account.email()),
        test ? List.of() : store.signInMethods(account.accountId()),
        test ? null : store.github(account.accountId()));
  }
}
