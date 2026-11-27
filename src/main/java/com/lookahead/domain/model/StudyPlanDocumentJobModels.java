package com.lookahead.domain.model;

/**
 * What one job of a study-plan document sweep page did. The abandoned-upload cleanup and the retired-snapshot cleanup
 * answer in the same words, so the two stores and the two jobs share these types (DLV-1016: each store had its own
 * identical copy).
 */
public final class StudyPlanDocumentJobModels {
    private StudyPlanDocumentJobModels() {}

    /** The most jobs one page of either sweep handles. */
    public static final int MAX_JOBS = 25;

    public enum Outcome { CLEANED, RETAINED, FAILED }

    /** One job of the page: its key and what happened to it. */
    public record JobResult(String key, Outcome outcome) {}
}
