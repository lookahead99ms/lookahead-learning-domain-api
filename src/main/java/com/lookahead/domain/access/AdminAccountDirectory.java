package com.lookahead.domain.access;

import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.domain.testaccounts.TestAccountPolicy;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/** Owner-only directory: Identity owns identity fields; Domain owns roles and access. */
@Service
public class AdminAccountDirectory {
    public record Row(UUID accountId,String username,String displayName,String email,Set<String> roles,
        boolean testAccount,boolean fullCurriculum,AccessSummaryService.Summary access,
        boolean canDowngrade,boolean canDelete,String signIn,boolean canDisable,boolean canEnable,boolean canRemove,int sameEmailAccounts,
        List<Map<String,Object>> signInMethods,String github) {
        /**
         * sameEmailAccounts (DLV-969 S36): other accounts, not deleted or test, with this verified email; signInMethods:
         * each sign-in of the account, oldest first, as {provider, email?}, test identities left out. github (DLV-969,
         * owner 2026-11-05): the GitHub profile link the person saved (cloud_account_profile_links), null when none or for
         * a test account, so the owner can add staff to repositories and workflows. The owner only; never logged.
         */
        public Row(UUID accountId,String username,String displayName,String email,Set<String> roles,boolean testAccount,boolean fullCurriculum,
                AccessSummaryService.Summary access,boolean canDowngrade,boolean canDelete,String signIn,boolean canDisable,boolean canEnable,boolean canRemove,int sameEmailAccounts,
                List<Map<String,Object>> signInMethods) {
            this(accountId,username,displayName,email,roles,testAccount,fullCurriculum,access,canDowngrade,canDelete,signIn,canDisable,canEnable,canRemove,sameEmailAccounts,signInMethods,null);
        }
        public Row(UUID accountId,String username,String displayName,String email,Set<String> roles,boolean testAccount,boolean fullCurriculum,
                AccessSummaryService.Summary access,boolean canDowngrade,boolean canDelete,String signIn,boolean canDisable,boolean canEnable,boolean canRemove) {
            this(accountId,username,displayName,email,roles,testAccount,fullCurriculum,access,canDowngrade,canDelete,signIn,canDisable,canEnable,canRemove,0);
        }
        public Row(UUID accountId,String username,String displayName,String email,Set<String> roles,boolean testAccount,boolean fullCurriculum,
                AccessSummaryService.Summary access,boolean canDowngrade,boolean canDelete,String signIn,boolean canDisable,boolean canEnable,boolean canRemove,int sameEmailAccounts) {
            this(accountId,username,displayName,email,roles,testAccount,fullCurriculum,access,canDowngrade,canDelete,signIn,canDisable,canEnable,canRemove,sameEmailAccounts,List.of());
        }
    }
    public record Page(List<Row> accounts,int page,boolean hasNext) {}
    private final AccountDirectory directory;
    private final StaffAccessService access;
    private final StaffAccessRepository staff;
    private final AccessSummaryService summaries;
    private final AdminDirectoryStore store;
    private final TestAccountPolicy localPolicy;
    private final AccountRemovalService removal;
    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @org.springframework.beans.factory.annotation.Autowired
    public AdminAccountDirectory(AccountDirectory directory,StaffAccessService access,StaffAccessRepository staff,
            AccessSummaryService summaries,AdminDirectoryStore store,TestAccountPolicy localPolicy,AccountRemovalService removal) {
        this.directory=directory;this.access=access;this.staff=staff;this.summaries=summaries;this.store=store;this.localPolicy=localPolicy;this.removal=removal;
    }
    /**
     * DLV-969: each row is about ten independent reads. They ran one row after another, so a page of 35 took about 7 s on
     * DEV. Rows now run on a small fixed pool and come back in page order. Every read here is a query: no row
     * writes, so a retry or an overlapping request can only repeat a read, never a change.
     */
    static final int ROW_PARALLELISM = 8;
    private final ExecutorService rowPool = Executors.newFixedThreadPool(ROW_PARALLELISM, runnable -> {
        Thread thread = new Thread(runnable, "admin-directory-row");
        thread.setDaemon(true);
        return thread;
    });
    public Page list(AccountPrincipal actor,String query,int page) {
        access.requireOwner(actor); // Before any identity request or database enumeration.
        var source=directory.list(query,page);
        var futures=source.accounts().stream()
            .map(account->CompletableFuture.supplyAsync(()->row(actor,account),rowPool)).toList();
        var rows=futures.stream().map(AdminAccountDirectory::await).toList();
        return new Page(rows,source.page(),source.hasNext());
    }

    /** The row's own failure (an AccountFailure, say) reaches the caller as itself, not wrapped. */
    private static Row await(CompletableFuture<Row> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        rowPool.shutdownNow();
    }

    private Row row(AccountPrincipal actor, AccountDirectory.Account account) {
        var assignment=staff.forAccount(account.accountId());
        boolean owner=actor.accountId().equals(account.accountId()) || store.author(account.accountId());
        Set<String> roles=owner?Set.of("owner"):assignment==null||assignment.roles().isEmpty()?Set.of("user"):assignment.roles();
        boolean full=owner||StaffPermissions.forRoles(false,assignment==null?Set.of():assignment.roles()).fullCurriculum()
            ||assignment!=null&&assignment.fullCurriculum();
        boolean test=store.testAccount(account.accountId());
        var summary=summaries.get(actor,account.accountId());
        boolean purchases=store.openPurchases(account.accountId());
        boolean grants=store.grants(account.accountId());
        boolean privileged=!roles.equals(Set.of("user")) || assignment!=null&&(assignment.fullCurriculum()||!assignment.topicGrants().isEmpty());
        boolean eligible=!owner&&!test;
        // DLV-969: Disable, Enable and Delete for DEV sign-in accounts (never the owner, an author or a test account).
        var options=owner?AccountRemovalService.Options.NONE:removal.options(actor.accountId(),account.accountId(),test);
        return new Row(account.accountId(),account.username(),account.displayName(),account.email(),roles,test,full,summary,
            eligible&&(privileged||purchases||grants||!"Free".equals(summary.label())),
            eligible&&localPolicy.local()&&!privileged&&!purchases&&!grants&&"Free".equals(summary.label()),
            options.signIn(),options.canDisable(),options.canEnable(),options.canRemove(),
            test?0:store.sameEmailAccounts(account.accountId(),account.email()),
            test?List.of():store.signInMethods(account.accountId()),
            test?null:store.github(account.accountId()));
    }

}
