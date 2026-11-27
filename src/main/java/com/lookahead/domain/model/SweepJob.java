package com.lookahead.domain.model;

/** The jobs the owner can run, by the name the request carries. DLV-1016: was nested in SweepRunner. */
public enum SweepJob {
    SUPPORT_REQUESTS("support-requests"), DELIVERY_INBOX("delivery-inbox"), ADMISSIONS("admissions"), MAINTENANCE("maintenance"), ALL("all");
    public final String id;
    SweepJob(String id) { this.id = id; }
    public boolean includes(SweepJob job) { return this == ALL || this == job; }
    /** The job with this request name, or {@code null} for anything else. */
    public static SweepJob named(String id) {
        for (SweepJob job : values()) if (job.id.equals(id)) return job;
        return null;
    }
}
