package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.Events;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: applies the catalog's missing steps in order, each one apply → verify → record, under the fenced
 * lock, and verifies an existing store read-only. There is no pipeline gate: the running release keeps serving
 * while data moves ahead of it (every step is backward compatible), and a release ahead of its data reports
 * DATA_VERSION_BEHIND through readiness instead of serving. The runner never repairs history, skips a number,
 * accepts a changed checksum or touches the owner binding.
 */
public final class DynamoMigrationRunner {
    static final Logger LOG = LoggerFactory.getLogger("lookahead.migration");
    static final Duration LEASE = Duration.ofMinutes(15);

    public record Outcome(long before, long after, List<String> applied, List<String> verified, boolean ahead) { }

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final DynamoMigrationCatalog catalog;
    private final DynamoMigrationLedger ledger;
    private final DynamoMigrationLock lock;
    private final Clock clock;

    public DynamoMigrationRunner(DynamoDbClient client, DynamoTableNames tables, DynamoMigrationCatalog catalog, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ledger = new DynamoMigrationLedger(client, tables);
        this.lock = new DynamoMigrationLock(client, tables);
    }

    /** Applies every step past CURRENT. Stops at the first failure; what was recorded stays recorded. */
    public Outcome migrate(String runId) {
        var lease = lock.acquire(runId, clock.instant(), LEASE);
        Outcome outcome;
        try {
            long before = checkedCurrent();
            var applied = new ArrayList<String>();
            for (int version = (int) before + 1; version <= catalog.latestVersion(); version++) {
                var step = catalog.step(version);
                lease = lock.renew(lease, clock.instant(), LEASE);
                Events.success(LOG, "migration.apply").addKeyValue("data.version", version).addKeyValue("data.step", step.name())
                        .log("Applying data step {} {}", version, step.name());
                try {
                    step.apply(client, tables);
                    step.verify(client, tables);
                } catch (RuntimeException failure) {
                    Events.failed(LOG, "migration.apply", "STEP_FAILED").addKeyValue("data.version", version).addKeyValue("data.step", step.name())
                            .addKeyValue("error.type", Events.types(failure)).log("Data step {} {} failed before it was recorded; CURRENT stays at {}", version, step.name(), version - 1);
                    throw failure;
                }
                ledger.record(new DynamoMigrationLedger.AppliedStep(version, step.name(), catalog.checksum(version), clock.instant(), runId), lease);
                applied.add(version + " " + step.name());
                Events.success(LOG, "migration.record").addKeyValue("data.version", version).addKeyValue("data.step", step.name())
                        .log("Recorded data step {} {}; CURRENT is now {}", version, step.name(), version);
            }
            long after = ledger.current();
            Events.success(LOG, "migration.migrate").addKeyValue("data.version", after)
                    .log("Migration complete: the data is at {}; this build knows {} step(s)", after, catalog.latestVersion());
            outcome = new Outcome(before, after, List.copyOf(applied), List.of(), after > catalog.latestVersion());
        } catch (RuntimeException failure) {
            // The step failure is the report; a release that fails too (the lease was taken over, the store went away) is attached, never substituted.
            try { lock.release(lease); } catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
            throw failure;
        }
        lock.release(lease);
        return outcome;
    }

    /** Read-only: history is contiguous, every known step's checksum matches and its effects hold, CURRENT agrees. */
    public Outcome verify() {
        long current = checkedCurrent();
        var verified = new ArrayList<String>();
        int known = (int) Math.min(current, catalog.latestVersion()); // latestVersion is an int, so the minimum fits
        for (int version = 1; version <= known; version++) {
            var step = catalog.step(version);
            step.verify(client, tables);
            verified.add(version + " " + step.name());
        }
        boolean ahead = current > catalog.latestVersion();
        Events.success(LOG, "migration.verify").addKeyValue("data.version", current)
                .log("Verified {} data step(s); the data is at {}{}", verified.size(), current,
                        ahead ? " (ahead of this build's catalog; later steps are not checked by it)" : "");
        return new Outcome(current, current, List.of(), List.copyOf(verified), ahead);
    }

    /** Read-only: the checked watermark as an outcome, one ledger read. */
    public Outcome status() {
        long current = checkedCurrent();
        return new Outcome(current, current, List.of(), List.of(), current > catalog.latestVersion());
    }

    /** The checked watermark: history is 1..CURRENT with the catalog's checksums for every version this build knows. */
    long checkedCurrent() {
        long current = ledger.current();
        var history = ledger.history();
        if (history.size() != current) throw new DynamoMigrationLedger.Conflict("Migration history has " + history.size() + " step(s) but CURRENT is " + current);
        for (int index = 0; index < history.size(); index++) {
            var step = history.get(index);
            int version = index + 1;
            if (step.version() != version) throw new DynamoMigrationLedger.Conflict("Migration history is missing step " + version);
            if (version <= catalog.latestVersion()) {
                if (!step.name().equals(catalog.step(version).name()))
                    throw new DynamoMigrationLedger.Conflict("Applied step " + version + " is named differently from this build's catalog");
                if (!step.checksum().equals(catalog.checksum(version)))
                    throw new DynamoMigrationLedger.Conflict("Applied step " + version + " has a different definition from this build's catalog");
            }
        }
        return current;
    }
}
