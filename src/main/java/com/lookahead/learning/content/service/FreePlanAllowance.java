package com.lookahead.learning.content.service;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.util.PlanJson;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Free allowances are checked after PlanService takes the owner row lock. DLV-970: the ledgers sit behind {@link FreePlanLedgerStore}. */
@Service
@Profile("accounts")
public class FreePlanAllowance {
    static final int MAX_ACTIVE = 2;
    static final int MAX_CREATIONS = 15;
    static final int MAX_DELETIONS = 3;
    static final long DELETION_WINDOW_DAYS = 21;

    private final FreePlanLedgerStore ledger;
    private final AccountRepository accounts;
    private final ProtectedContentPolicy publication;
    private final PlanJson json;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FreePlanAllowance(FreePlanLedgerStore ledger, AccountRepository accounts,
            ProtectedContentPolicy publication, PlanJson json) {
        this(ledger, accounts, publication, json, Clock.systemUTC());
    }

    FreePlanAllowance(FreePlanLedgerStore ledger, AccountRepository accounts,
            ProtectedContentPolicy publication, PlanJson json, Clock clock) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.publication = publication;
        this.json = json;
        this.clock = clock;
    }

    public boolean freeAccount(UUID owner) {
        // DLV-968: the staff documents scope is not course access; an admin without courses is still a free account.
        return com.lookahead.learning.content.util.CatalogIds.curriculumGrants(accounts.findTopicGrants(owner)).stream()
                .noneMatch(grant -> !grant.startsWith("content:"));
    }

    public boolean freeContent(Set<String> contentIds) {
        return !contentIds.isEmpty() && publication.freeContentIds().containsAll(contentIds);
    }

    public boolean beforeCreate(UUID owner, Set<String> contentIds) {
        if (!freeAccount(owner)) return false;
        if (!freeContent(contentIds))
            throw new AccountFailure(403, "COURSE_ACCESS_REQUIRED", "Get course access before saving this plan");
        if (activeFreePlans(owner, null) >= MAX_ACTIVE)
            throw activeLimit(owner, "plan.create", "Archive or delete a free plan before saving another");
        if (creations(owner) >= MAX_CREATIONS) throw lifetimeLimit(owner, "plan.create");
        return true;
    }

    public void afterCreate(UUID owner, UUID planId, boolean freeCreation) {
        if (freeCreation) ledger.recordCreation(owner, planId);
    }

    public void beforeActivation(UUID owner, UUID planId, Set<String> contentIds) {
        if (freeAccount(owner) && freeContent(contentIds) && activeFreePlans(owner, planId) >= MAX_ACTIVE)
            throw activeLimit(owner, "plan.activate", "Archive or delete a free plan before activating another");
    }

    public boolean beforeFreeScopeChange(UUID owner, UUID planId, Set<String> priorContent, Set<String> nextContent,
            boolean active) {
        if (!freeAccount(owner) || freeContent(priorContent) || !freeContent(nextContent)) return false;
        if (active && activeFreePlans(owner, planId) >= MAX_ACTIVE)
            throw activeLimit(owner, "plan.version", "Archive or delete a free plan before changing this plan");
        if (ledger.creationRecorded(owner, planId)) return false;
        if (creations(owner) >= MAX_CREATIONS) throw lifetimeLimit(owner, "plan.version");
        return true;
    }

    /** Free plan allowance hits: one {@code lookahead.limit} line each (2 active, 15 in a lifetime, 3 deletions in 21 days). */
    private static AccountFailure activeLimit(UUID owner, String action, String message) {
        Events.limit(action, "FREE_ACTIVE_PLAN_LIMIT", "free-active-plans", MAX_ACTIVE, "at once").addKeyValue("account.id", owner)
                .log("Free plan limit hit for account {}: at most {} active free plans at once", owner, MAX_ACTIVE);
        return new AccountFailure(409, "FREE_ACTIVE_PLAN_LIMIT", message);
    }

    private static AccountFailure lifetimeLimit(UUID owner, String action) {
        Events.limit(action, "FREE_LIFETIME_PLAN_LIMIT", "free-plan-creations", MAX_CREATIONS, "lifetime").addKeyValue("account.id", owner)
                .log("Free plan limit hit for account {}: all {} free plan creations of its lifetime are used", owner, MAX_CREATIONS);
        return new AccountFailure(409, "FREE_LIFETIME_PLAN_LIMIT", "This account has used its 15 free plan creations");
    }

    public void beforeDelete(UUID owner) {
        if (!freeAccount(owner)) return;
        var since = Instant.now(clock).minusSeconds(DELETION_WINDOW_DAYS * 86400);
        int count = ledger.deletionsSince(owner, since);
        if (count >= MAX_DELETIONS) {
            Instant oldest = ledger.oldestDeletionSince(owner, since);
            String available = oldest == null ? "after the current window" :
                    oldest.plusSeconds(DELETION_WINDOW_DAYS * 86400)
                            .atOffset(ZoneOffset.UTC).toString();
            Events.limit("plan.delete", "FREE_DELETE_LIMIT", "free-plan-deletions", MAX_DELETIONS, DELETION_WINDOW_DAYS + "d").addKeyValue("account.id", owner)
                    .log("Free plan limit hit for account {}: at most {} deletions in each rolling {} days; next deletion allowed {}",
                        owner, MAX_DELETIONS, DELETION_WINDOW_DAYS, available);
            throw new AccountFailure(429, "FREE_DELETE_LIMIT",
                    "Three free-account deletions are allowed in each rolling 21-day window. You can delete again " + available);
        }
    }

    public void afterDelete(UUID owner, UUID planId, boolean freeAccountAtDelete) {
        if (freeAccountAtDelete) ledger.recordDeletion(owner, planId);
    }

    public int creations(UUID owner) {
        return ledger.creations(owner);
    }

    private int activeFreePlans(UUID owner, UUID excludedPlan) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return ledger.activeFreePlans(owner, excludedPlan, today, this::freeContent);
    }
}
