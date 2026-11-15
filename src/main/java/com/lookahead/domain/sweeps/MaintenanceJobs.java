package com.lookahead.domain.sweeps;

import java.util.List;

/**
 * DLV-970: the bounded maintenance work the sweeps run in dynamodb mode, in registration order. A job does one bounded
 * page of work per {@link Job#run} call and says whether more is waiting; the sweep calls it again, up to
 * {@link RuntimeSweeps#MAX_PAGES} pages per sweep, and the next sweep starts afresh. Lane D registers the study-plan
 * document jobs; other lanes register their cleanup and erasure jobs either by exposing a {@link Job} bean (collected
 * at start) or by calling {@link #register} from their configuration. A job that throws is logged by the sweep and
 * does not stop the others.
 */
public interface MaintenanceJobs {
    /** What one page did: how many jobs were cleaned, retained for a later sweep or failed, and whether a page follows. */
    record Page(int cleaned, int retained, int failed, boolean more) {
        public Page {
            if (cleaned < 0 || retained < 0 || failed < 0) throw new IllegalArgumentException("Counts cannot be negative");
        }
        public static final Page NOTHING = new Page(0, 0, 0, false);
    }

    /** One registered job; {@code name} is stable, lower-case and appears in the sweep's answer and log line. */
    interface Job {
        String name();
        /** One bounded page; {@code afterFirstPage} is false for the first call of a sweep and true for the continuations. */
        Page run(boolean afterFirstPage);
    }

    void register(Job job);

    /** The registered jobs in registration order. */
    List<Job> jobs();
}
