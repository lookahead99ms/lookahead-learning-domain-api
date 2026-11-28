package com.lookahead.domain.db.store.dynamodb;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Immutable release requirement supplied by code, not deployment properties. Names are numbered
 * from one.
 */
// A technology helper of db.store.dynamodb: "Dynamo data version" is its subject, so the word stays
// in the name (DLV-1016).
public record DynamoDataRequirement(List<String> stepNames) {
  private static final int MAX_DISPLAYED_STEPS = 8;

  public DynamoDataRequirement {
    Objects.requireNonNull(stepNames, "stepNames");
    if (stepNames.isEmpty() || stepNames.size() > 10_000) {
      throw new IllegalArgumentException("Invalid release data requirement");
    }
    for (String name : stepNames) {
      if (name == null || !name.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || name.length() > 80) {
        throw new IllegalArgumentException("Invalid migration step name");
      }
    }
    if (stepNames.stream().distinct().count() != stepNames.size()) {
      throw new IllegalArgumentException("Duplicate migration step name");
    }
    stepNames = List.copyOf(stepNames);
  }

  public int minimumVersion() {
    return stepNames.size();
  }

  public String behindMessage(long currentVersion) {
    if (currentVersion < 0 || currentVersion >= minimumVersion()) {
      throw new IllegalArgumentException("Data version is not behind");
    }
    int start = Math.toIntExact(currentVersion);
    int end = Math.min(minimumVersion(), start + MAX_DISPLAYED_STEPS);
    String missing =
        IntStream.range(start, end)
            .mapToObj(i -> (i + 1) + " " + stepNames.get(i))
            .collect(Collectors.joining(", "));
    int omitted = minimumVersion() - end;
    if (omitted > 0) missing += ", and " + omitted + " more";
    return "the data is at "
        + currentVersion
        + "; this release needs "
        + minimumVersion()
        + " (missing "
        + missing
        + "); run the migration before deploying";
  }
}
