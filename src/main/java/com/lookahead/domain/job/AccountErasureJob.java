package com.lookahead.domain.job;

import com.lookahead.domain.db.store.access.AccountErasureStore;
import com.lookahead.domain.db.store.access.AccountRemovalStore;
import com.lookahead.domain.log.EventLog;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DLV-970 lane G: the {@code account-erasure} maintenance job. Each sweep page takes up to
 * {@link AccountErasureStore#MAX_QUEUED} queued deletions and finishes the ones whose removal job is still in its
 * ERASE phase; a queue entry without a pending job is dropped once it is older than {@link #GRACE}; a failed erasure stays queued for the next sweep.
 */
@Component
public class AccountErasureJob implements MaintenanceJobRegistry.Job {
    public static final String NAME = "account-erasure";
    /** A queue entry younger than this whose job is not pending yet is a Delete still between its queue and its state transition. */
    public static final Duration GRACE = Duration.ofMinutes(10);
    private final AccountErasureStore erasure;

    @Autowired
    public AccountErasureJob(AccountRemovalStore removal) { this(removal.erasure()); }
    public AccountErasureJob(AccountErasureStore erasure) { this.erasure = erasure; }

    @Override public String name() { return NAME; }
    @Override public MaintenanceJobRegistry.Page run(boolean afterFirstPage) {
        // DLV-1016: the loop, the grace rule and the "did not complete" line were the store's resumePage and resume; the store keeps the reads and writes.
        var page = erasure.queued(AccountErasureStore.MAX_QUEUED);
        int finished = 0, dropped = 0, failed = 0;
        for (AccountErasureStore.Queued entry : page) {
            if (!erasure.pending(entry.id())) {
                // A Delete queues before it transitions: a young entry without a job is still in flight, an old one is stale.
                if (entry.queuedAt() != null && entry.queuedAt().plus(GRACE).isAfter(erasure.now())) continue;
                erasure.drop(entry.id()); dropped++; continue;
            }
            // The job item is read once more just before the erasure (the read the store's resume made): a run that another sweep or a retried Delete finished in between is counted as not finished here, as it always was.
            if (!erasure.pending(entry.id())) { failed++; continue; }
            try { erasure.erase(entry.actor(), entry.id()); finished++; }
            catch (RuntimeException failure) {
                EventLog.failed(EventLog.EVENT, "account.erasure", "UNEXPECTED").addKeyValue("target.id", entry.id()).addKeyValue("error.type", EventLog.types(failure))
                    .log("Erasure of deleted account {} did not complete ({}); the next run tries again", entry.id(), EventLog.types(failure));
                failed++;
            }
        }
        // A failed erasure stays at the head of the queue: a further page in this sweep would only retry it, so the next sweep does.
        return new MaintenanceJobRegistry.Page(finished + dropped, 0, failed, page.size() == AccountErasureStore.MAX_QUEUED && failed == 0);
    }
}
