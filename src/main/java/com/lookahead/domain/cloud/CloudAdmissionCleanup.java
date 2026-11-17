package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import java.time.Duration;

/**
 * DLV-969: removes waiting rows nobody admitted within {@link #WAITING_EXPIRY}. Cloud mode only, under a
 * store's own serialisation (one conditional delete per account) so two tasks never both remove one account. Admitted
 * accounts are never touched.
 * DLV-971 (owner, 2026-11-06): run by {@link com.lookahead.domain.sweeps.RuntimeSweeps} at start and every 4 hours
 * (it was hourly on its own schedule).
 */
public final class CloudAdmissionCleanup {
    public static final Duration WAITING_EXPIRY=Duration.ofHours(48);
    static final String LOCK="cloud-admission-cleanup";
    private final AdmissionCleanupStore store;
    /** DLV-970: the store decides how expiry is serialised (conditional writes on DynamoDB). */
    public CloudAdmissionCleanup(AdmissionCleanupStore store){this.store=store;}

    /** One guarded run: the number of waiting rows removed; 0 when another task holds the lock or the run did not complete (logged). */
    public int run() {
        try {return expire();}
        catch(RuntimeException failure){
            Events.failed(Events.EVENT,"admission.cleanup","UNEXPECTED").addKeyValue("error.type",Events.types(failure))
                .log("Admission cleanup skipped this run: {}; the next run tries again",Events.types(failure));
            return 0;
        }
    }
    /**
     * One line per run: how many waiting rows were removed, or that another task holds the lock; and one line per
     * removed account. The lines are written once the removal commits.
     * @return the number of waiting rows removed; 0 when another task holds the lock.
     */
    public int expire() { return store.expire(); }
}
