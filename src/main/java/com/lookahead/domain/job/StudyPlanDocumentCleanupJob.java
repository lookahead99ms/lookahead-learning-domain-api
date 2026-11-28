package com.lookahead.domain.job;

import com.lookahead.domain.db.store.plan.StudyPlanDocumentCleanupStore;
import com.lookahead.domain.model.StudyPlanDocumentJobModels;
import java.util.Objects;

/**
 * Abandoned uploads: the cursor keeps the first page's cutoff for the whole sweep, as the
 * maintenance requires.
 */
public final class StudyPlanDocumentCleanupJob implements MaintenanceJobRegistry.Job {
  public static final String DOCUMENTS = "study-plan-documents";
  private final StudyPlanDocumentCleanupStore maintenance;
  private StudyPlanDocumentCleanupStore.Cursor cursor;

  public StudyPlanDocumentCleanupJob(StudyPlanDocumentCleanupStore maintenance) {
    this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
  }

  @Override
  public String name() {
    return DOCUMENTS;
  }

  @Override
  public synchronized MaintenanceJobRegistry.Page run(boolean afterFirstPage) {
    if (!afterFirstPage) cursor = null;
    var batch = maintenance.cleanDue(StudyPlanDocumentJobModels.MAX_JOBS, cursor);
    cursor = batch.next();
    return MaintenanceJobRegistry.Page.counted(batch.jobs(), cursor != null);
  }
}
