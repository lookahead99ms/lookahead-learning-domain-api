package com.lookahead.domain.access;

import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.domain.testaccounts.TestAccountPolicy;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Owner-only directory: Identity owns identity fields; Domain owns roles and access. */
@Service
public class AdminAccountDirectory {
    public record Row(UUID accountId,String username,String displayName,String email,Set<String> roles,
        boolean testAccount,boolean fullCurriculum,AccessSummaryService.Summary access,
        boolean canDowngrade,boolean canDelete,String signIn,boolean canDisable,boolean canEnable,boolean canRemove) {}
    public record Page(List<Row> accounts,int page,boolean hasNext) {}
    private final AccountDirectory directory;
    private final StaffAccessService access;
    private final StaffAccessRepository staff;
    private final AccessSummaryService summaries;
    private final JdbcTemplate jdbc;
    private final TestAccountPolicy localPolicy;
    private final AccountRemovalService removal;
    public AdminAccountDirectory(AccountDirectory directory,StaffAccessService access,StaffAccessRepository staff,
            AccessSummaryService summaries,JdbcTemplate jdbc,TestAccountPolicy localPolicy,AccountRemovalService removal) {
        this.directory=directory;this.access=access;this.staff=staff;this.summaries=summaries;this.jdbc=jdbc;this.localPolicy=localPolicy;this.removal=removal;
    }
    public Page list(AccountPrincipal actor,String query,int page) {
        access.requireOwner(actor); // Before any identity request or database enumeration.
        var source=directory.list(query,page);
        var rows=source.accounts().stream().map(account->{
            var assignment=staff.forAccount(account.accountId());
            boolean owner=actor.accountId().equals(account.accountId()) || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access)",Boolean.class,account.accountId()));
            Set<String> roles=owner?Set.of("owner"):assignment==null||assignment.roles().isEmpty()?Set.of("user"):assignment.roles();
            boolean full=owner||StaffPermissions.forRoles(false,assignment==null?Set.of():assignment.roles()).fullCurriculum()
                ||assignment!=null&&assignment.fullCurriculum();
            boolean test=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)",Boolean.class,account.accountId()));
            var summary=summaries.get(actor,account.accountId());
            boolean purchases=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL)",Boolean.class,account.accountId()));
            boolean grants=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_grants WHERE account_id=?)",Boolean.class,account.accountId()));
            boolean privileged=!roles.equals(Set.of("user")) || assignment!=null&&(assignment.fullCurriculum()||!assignment.topicGrants().isEmpty());
            boolean eligible=!owner&&!test;
            // DLV-969: Disable, Enable and Delete for DEV sign-in accounts (never the owner, an author or a test account).
            var options=owner?AccountRemovalService.Options.NONE:removal.options(actor.accountId(),account.accountId(),test);
            return new Row(account.accountId(),account.username(),account.displayName(),account.email(),roles,test,full,summary,
                eligible&&(privileged||purchases||grants||!"Free".equals(summary.label())),
                eligible&&localPolicy.local()&&!privileged&&!purchases&&!grants&&"Free".equals(summary.label()),
                options.signIn(),options.canDisable(),options.canEnable(),options.canRemove());
        }).toList();
        return new Page(rows,source.page(),source.hasNext());
    }
}
