package com.lookahead.domain.db.store.plan;

/**
 * Test only: the three plan interfaces as one type, so one mock can stand for the store class that
 * implements all three (DLV-1016).
 */
public interface StudyPlanParts extends StudyPlan, StudyPlanVersions, StudyPlanReceipts {}
