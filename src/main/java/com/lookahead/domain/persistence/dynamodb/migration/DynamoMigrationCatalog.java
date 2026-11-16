package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.persistence.dynamodb.DynamoDataRequirement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** The ordered, immutable list of steps a build ships. Numbers run from one without gaps. */
public final class DynamoMigrationCatalog {
    private final List<DynamoMigrationStep> steps;
    private final List<String> checksums;

    public DynamoMigrationCatalog(List<DynamoMigrationStep> steps) {
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty() || steps.size() > 10_000) throw new IllegalArgumentException("Invalid migration catalog");
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            if (step == null || step.version() != index + 1) throw new IllegalArgumentException("Migration steps must be numbered consecutively from 1");
            if (step.definition() == null || step.definition().isBlank() || step.definition().length() > 64 * 1024)
                throw new IllegalArgumentException("Migration step " + step.version() + " needs a bounded definition");
        }
        // The requirement enforces the name rule and uniqueness.
        new DynamoDataRequirement(steps.stream().map(DynamoMigrationStep::name).toList());
        this.steps = List.copyOf(steps);
        this.checksums = this.steps.stream().map(DynamoMigrationCatalog::checksumOf).toList();
    }

    public List<DynamoMigrationStep> steps() { return steps; }

    public int latestVersion() { return steps.size(); }

    public DynamoMigrationStep step(int version) {
        if (version < 1 || version > steps.size()) throw new IllegalArgumentException("Unknown migration step " + version);
        return steps.get(version - 1);
    }

    /** SHA-256 over version, name and definition: a changed definition under an applied number is detected. */
    public String checksum(int version) { return checksums.get(step(version).version() - 1); }

    public DynamoDataRequirement requirement() {
        return new DynamoDataRequirement(steps.stream().map(DynamoMigrationStep::name).toList());
    }

    static String checksumOf(DynamoMigrationStep step) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(("v" + step.version() + "\n" + step.name() + "\n" + step.definition()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }
}
