package com.lookahead.learning.content.repository;

// DLV-970 cross-lane: provided by lane D
/** The sweep runner that calls registered DynamoDB maintenance jobs (deleted-plan purge) every cycle. */
public interface PlanMaintenanceJobs {
    void register(String name, Runnable job);
}
