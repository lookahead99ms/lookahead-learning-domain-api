package com.lookahead.domain.access;

import com.lookahead.domain.sweeps.MaintenanceJobs;
import org.springframework.stereotype.Component;

/**
 * DLV-970 lane G: the {@code account-erasure} maintenance job. Each sweep page takes up to
 * {@link DynamoAccountErasure#MAX_QUEUED} queued deletions and finishes the ones whose removal job is still in its
 * ERASE phase ({@link DynamoAccountErasure#resumePage}); a failed erasure stays queued for the next sweep.
 */
@Component
public class DynamoAccountErasureJob implements MaintenanceJobs.Job {
    public static final String NAME = "account-erasure";
    private final DynamoAccountErasure erasure;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoAccountErasureJob(DynamoAccountRemovalStore removal) { this(removal.erasure()); }
    public DynamoAccountErasureJob(DynamoAccountErasure erasure) { this.erasure = erasure; }

    @Override public String name() { return NAME; }
    @Override public MaintenanceJobs.Page run(boolean afterFirstPage) {
        var resumed = erasure.resumePage(DynamoAccountErasure.MAX_QUEUED);
        // A failed erasure stays at the head of the queue: a further page in this sweep would only retry it, so the next sweep does.
        return new MaintenanceJobs.Page(resumed.finished() + resumed.dropped(), 0, resumed.failed(), resumed.more() && resumed.failed() == 0);
    }
}
