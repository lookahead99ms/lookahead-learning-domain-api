package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.Events;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasVerifier;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.lookahead.domain.cloud.CloudAccountItems.account;
import static com.lookahead.domain.cloud.CloudAccountItems.attrs;
import static com.lookahead.domain.cloud.CloudAccountItems.get;
import static com.lookahead.domain.cloud.CloudAccountItems.item;
import static com.lookahead.domain.cloud.CloudAccountItems.n;
import static com.lookahead.domain.cloud.CloudAccountItems.s;
import static com.lookahead.domain.cloud.CloudAccountItems.time;

/**
 * DLV-970 step 5 (owner decision 2026-11-17: import accounts and access only). On DEV this step read the PostgreSQL
 * platform database once (run 44162520740, 2026-11-17) and wrote every real account into the DynamoDB store with its
 * PostgreSQL account id; its result is the {@code IMPORT#account-access / RESULT} marker naming those accounts. The
 * PostgreSQL source was retired the same day (DLV-970, owner: "delete all Postgres things"), with its JDBC read code:
 * no store will ever import from PostgreSQL again (PROD starts on DynamoDB).
 *
 * <p>The step keeps its number, name and definition text, because the DEV ledger holds that checksum and
 * {@link DynamoMigrationRunner} refuses a changed definition. {@link #apply} on a store that already carries the
 * marker (DEV) changes nothing; on a store without one (a fresh local store, PROD) it writes the marker with zero
 * accounts, which is exactly what the import of an empty source did. {@link #verify} is unchanged: it reads the marker
 * and checks every account it names is there as imported.
 */
final class AccountAccessImport implements DynamoMigrationStep {
    static final int VERSION = 5;
    static final String NAME = "account-access-import";
    static final String SOURCE = "postgresql";
    static final String MARKER_PK = "IMPORT#account-access", MARKER_SK = "RESULT";
    /** The DEV run that read the retired PostgreSQL source; the marker it wrote is the step's only remaining input. */
    static final String DEV_IMPORT_RUN = "44162520740";
    /** PostgreSQL sequence → the step-4 counter partition it continued (verify still checks both counters). */
    static final Map<String, String> SEQUENCES = new LinkedHashMap<>(Map.of("review_record_seq", "SEQUENCE#review-record", "support_request_seq", "SEQUENCE#support-request"));

    @Override public int version() { return VERSION; }
    @Override public String name() { return NAME; }
    @Override public String definition() {
        return "Import the real accounts of the PostgreSQL platform database (cloud_accounts rows without a test_account_registry row, not on the"
                + " test issuer, not removed) into user-account-details and administrator-audit-events with their PostgreSQL ids: META and CONTROL"
                + " (importedFrom postgresql), cloud_identities as IDENTITY bindings, EMAIL carriers and sentinels, the DIRECTORY projection, WAITING"
                + " while not admitted, the staff_access assignment with its STAFF pointer and staff_access_events, cloud_admission_events as"
                + " ADMISSION events at their recorded times, staff_aliases as immutable number claims; live pending staff invitations as INVITE"
                + " pointers; STAFF_ROLE counters raised to the highest imported number; SEQUENCE#review-record and SEQUENCE#support-request raised"
                + " to the PostgreSQL sequence values. Waiting sign-in rows recorded before the import are removed with an expire event; an admitted"
                + " account unknown to the import is refused. One transaction per account, imported accounts are skipped on a rerun; the result is"
                + " IMPORT#account-access / RESULT. A local store without a PostgreSQL source imports nothing.";
    }

    /**
     * Nothing is read but the marker. A store that carries one (DEV, imported on 2026-11-17) is left as it is; a store
     * without one has nothing to import (the source is retired) and gets the empty marker, insert-only.
     */
    @Override public void apply(DynamoDbClient client, DynamoTableNames tables) {
        String table = tables.userAccountDetailsTableName();
        if (!get(client, table, MARKER_PK, MARKER_SK).isEmpty()) {
            Events.success(DynamoMigrationRunner.LOG, "migration.import").addKeyValue("count", 0)
                    .log("Account import: the store already carries IMPORT#account-access / RESULT (DEV import, run {}); nothing to do", DEV_IMPORT_RUN);
            return;
        }
        var set = attrs("accountIds", DynamoItems.strings(Set.of()), "importedAccounts", n(0), "removedWaiting", n(0), "source", s(SOURCE), "importedAt", time(Instant.now()));
        try { DynamoWrites.transaction(client).insertOnly(table, item(MARKER_PK, MARKER_SK, set)).commit(); }
        catch (DynamoWrites.Conflict raced) { /* another run wrote the marker meanwhile; verify reads whichever is there */ }
        Events.success(DynamoMigrationRunner.LOG, "migration.import").addKeyValue("count", 0)
                .log("Account import: no PostgreSQL source (retired 2026-11-17 after the DEV import, run {}); the empty result marker is written", DEV_IMPORT_RUN);
    }

    /** Read-only, on the store alone: the marker names every imported account, each one is there as imported; the alias invariants hold. */
    @Override public void verify(DynamoDbClient client, DynamoTableNames tables) {
        String table = tables.userAccountDetailsTableName();
        var marker = get(client, table, MARKER_PK, MARKER_SK);
        if (marker.isEmpty()) throw new IllegalStateException("Account import result is missing");
        DynamoItems.requireSchema(marker, 1);
        for (String id : DynamoItems.stringSet(marker, "accountIds")) {
            var control = get(client, table, account(UUID.fromString(id)), "CONTROL");
            if (control.isEmpty() || !SOURCE.equals(DynamoItems.stringOrNull(control, "importedFrom"))) throw new IllegalStateException("An imported account is missing from the store");
        }
        new DynamoStaffAliasVerifier(client, tables).verify();
        for (String partition : SEQUENCES.values()) {
            var counter = get(client, table, partition, "COUNTER");
            if (counter.isEmpty() || DynamoItems.number(counter, "lastAssigned") < 0) throw new IllegalStateException("Counter " + partition + " is missing or negative");
        }
    }

    /** The account ids the marker names, for tests and operators reading the result. */
    static Set<String> importedAccountIds(DynamoDbClient client, DynamoTableNames tables) {
        return new TreeSet<>(DynamoItems.stringSet(get(client, tables.userAccountDetailsTableName(), MARKER_PK, MARKER_SK), "accountIds"));
    }
}
