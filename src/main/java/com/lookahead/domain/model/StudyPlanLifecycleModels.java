package com.lookahead.domain.model;

import java.time.*;
import java.util.*;

/**
 * One plan's lifecycle row. DLV-1016: these were nested in StudyPlanLifecycleService; the store
 * uses them too, so they live with the model.
 */
public final class StudyPlanLifecycleModels {
  private StudyPlanLifecycleModels() {}

  /**
   * An active plan reads as archived this many days after its last day; the free allowance stops
   * counting it then.
   */
  public static final int ARCHIVE_AFTER_DAYS = 90;

  public record Entry(
      UUID id, String state, LocalDate start, int days, int minutes, Set<String> subjects) {
    public LocalDate end() {
      return start.plusDays(days);
    }

    public String effective(LocalDate today) {
      return state.equals("active") && !today.isBefore(end().plusDays(ARCHIVE_AFTER_DAYS))
          ? "archived"
          : state;
    }
  }
}
