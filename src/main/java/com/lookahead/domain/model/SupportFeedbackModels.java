package com.lookahead.domain.model;

import java.util.*;

/**
 * The support receipt. DLV-1016: these were nested in SupportFeedbackService; the store uses them
 * too, so they live with the model.
 */
public final class SupportFeedbackModels {
  private SupportFeedbackModels() {}

  public record Receipt(String reference, String status) {}
}
