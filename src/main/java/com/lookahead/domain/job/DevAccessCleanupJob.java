package com.lookahead.domain.job;

import com.lookahead.domain.db.store.signin.DevAccessCleanup;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.util.SignInRules;
import java.time.Instant;
import java.util.UUID;

/**
 * DLV-969: removes the waiting accounts nobody admitted within {@link SignInRules#WAITING_EXPIRY}. DEV and PROD only. Admitted
 * accounts are never touched, and a waiting account the owner disabled is kept. DLV-1016: this job reads the waiting
 * list, applies those rules and writes the run's log lines; {@link DevAccessCleanup} makes each removal as one
 * conditional transaction, so two tasks never both remove one account.
 * DLV-971 (owner, 2026-11-06): run by {@link SweepRunner} at start and every 4 hours (it was hourly on its own schedule).
 */
public final class DevAccessCleanupJob {
    private final DevAccessCleanup store;
    public DevAccessCleanupJob(DevAccessCleanup store){this.store=store;}

    /** One guarded run: the number of waiting accounts removed; 0 when the run did not complete (logged). */
    public int run() {
        try {return expire();}
        catch(RuntimeException failure){
            EventLog.failed(EventLog.EVENT,"admission.cleanup","UNEXPECTED").addKeyValue("error.type",EventLog.types(failure))
                .log("Admission cleanup skipped this run: {}; the next run tries again",EventLog.types(failure));
            return 0;
        }
    }
    /**
     * One run over the waiting list, oldest request first. One line per removed account, written once its removal has
     * committed, and one line for the run with the number removed.
     * @return the number of waiting accounts removed.
     */
    public int expire() {
        Instant now=store.now(),deadline=now.minus(SignInRules.WAITING_EXPIRY);
        int removed=0;
        for(var waiting:store.waiting()){
            if(!waiting.requestedAt().isBefore(deadline))continue;
            UUID id=waiting.accountId();
            var candidate=store.read(waiting);
            if(!candidate.exists()||candidate.admitted()){
                // A waiting row left behind (an admission or removal that lost its race with a crash): drop it alone.
                store.dropRow(waiting);
                continue;
            }
            // DLV-971: a waiting account the owner disabled keeps its state and its block; only untouched requests expire.
            if(!candidate.active())continue;
            // An admission or another task was first: skipped, never retried.
            if(!store.expire(candidate,now))continue;
            removed++;
            EventLog.success(EventLog.EVENT,"admission.expired").addKeyValue("target.id",id)
                .log("Waiting account {} expired: not admitted within {} h, removed with its sign-in identities",id,SignInRules.WAITING_EXPIRY.toHours());
        }
        int count=removed;
        EventLog.success(EventLog.EVENT,"admission.cleanup").addKeyValue("count",count).log("Admission cleanup removed {} expired waiting account(s)",count);
        return removed;
    }
}
