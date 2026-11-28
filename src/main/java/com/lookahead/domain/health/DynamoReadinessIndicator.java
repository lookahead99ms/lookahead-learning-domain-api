package com.lookahead.domain.health;

import com.lookahead.domain.db.store.dynamodb.DynamoReadiness;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.HealthLog;
import com.lookahead.domain.model.HealthDiagnosis;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The {@code dynamodb} readiness indicator, registered by {@code
 * config.DynamoPersistenceConfiguration}. It asks {@link DynamoReadiness} (the store reads) and
 * writes the readiness line when one is due. It never writes or migrates; a store behind the
 * catalog reports DATA_VERSION_BEHIND until the migration task has run.
 */
public final class DynamoReadinessIndicator implements HealthIndicator {
  private final DynamoReadiness store;
  private final HealthLog healthLog;

  /**
   * DLV-1016: the indicator is given the store reads; it used to take the DynamoDB client and build
   * them itself.
   */
  public DynamoReadinessIndicator(DynamoReadiness store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.healthLog =
        new HealthLog(
            "dynamodb",
            "DynamoDB tables answer and the release data requirement is met",
            Objects.requireNonNull(clock, "clock"));
  }

  @Override
  public Health health() {
    HealthDiagnosis down = store.diagnose();
    EventLog.readiness(healthLog, down);
    return down == null ? Health.up().build() : Health.down().build();
  }
}
