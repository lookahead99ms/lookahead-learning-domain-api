package com.lookahead.domain.compatibility;

/**
 * Persisted identifiers from the first installed Domain API schema, before the service rename.
 *
 * <p>These are compatibility identifiers, not application names or alternate accepted roles.
 * Retaining them preserves installed data, grants, and the immutable V1 migration checksum.
 * Renaming these database objects requires a separate coordinated data migration.
 */
public final class LegacyStorageNames {
    public static final String RUNTIME_ROLE = "lookahead_platform_app";
    public static final String MIGRATOR_ROLE = "lookahead_platform_migrator";
    public static final String SUBJECTS_TABLE = "platform_subjects";
    public static final String INITIAL_MIGRATION_RESOURCE = "db/domain/V1__platform.sql";

    private LegacyStorageNames() {}
}
