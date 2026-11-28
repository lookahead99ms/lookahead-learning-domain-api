package com.lookahead.domain.job;

import com.lookahead.domain.db.store.plan.StudyPlanDocumentRetirementStore;
import com.lookahead.domain.model.StudyPlanDocumentJobModels;
import java.util.Objects;

/** Retired snapshots: a plain key continuation within one sweep. */
public final class StudyPlanDocumentRetirementJob implements MaintenanceJobRegistry.Job {
  public static final String RETIRED = "retired-study-plan-documents";
  private final StudyPlanDocumentRetirementStore retirement;
  private String afterKey;

  public StudyPlanDocumentRetirementJob(StudyPlanDocumentRetirementStore retirement) {
    this.retirement = Objects.requireNonNull(retirement, "retirement");
  }

  @Override
  public String name() {
    return RETIRED;
  }

  @Override
  public synchronized MaintenanceJobRegistry.Page run(boolean afterFirstPage) {
    if (!afterFirstPage) afterKey = null;
    var page = retirement.cleanPage(StudyPlanDocumentJobModels.MAX_JOBS, afterKey);
    afterKey = page.afterKey();
    return MaintenanceJobRegistry.Page.counted(page.jobs(), afterKey != null);
  }
}
