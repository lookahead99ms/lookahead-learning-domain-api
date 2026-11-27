package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.model.StudyPlanLifecycleModels;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DLV-970: the lifecycle rows behind {@code StudyPlanLifecycleService}, which keeps every date and reservation rule.
 * {@link StudyPlanLifecycleStore} keeps
 * {@code ACCOUNT#id / LIFECYCLE#planId} items read strongly and written under the owner revision, which is what makes
 * cross-plan course/day reservations safe.
 */
public interface StudyPlanLifecycle {
    /** Every lifecycle row of the owner ordered by plan id. */
    List<StudyPlanLifecycleModels.Entry> entries(UUID owner);
    /** Changes the state of an existing row; no row, no change. */
    void setState(UUID owner, UUID id, String state);
    /** Inserts or replaces the row as active with these dates. */
    void reserve(UUID owner, UUID id, LocalDate start, int days, int minutes, Set<String> subjects);
}
