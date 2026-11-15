package com.lookahead.domain.cloud;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.Events;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the 48-hour cleanup on DynamoDB. The {@code WAITING / ACCOUNT#requestedAt#id} projection lists the
 * candidates; each still-waiting, untouched account is removed in one transaction conditioned on its control revision
 * and {@code admitted=false}, so an admission that lands first wins and a stale cleanup removes nothing. Two tasks
 * running at once simply split the work: a lost condition is skipped, never retried.
 */
public final class DynamoAdmissionCleanupStore implements AdmissionCleanupStore {
    private final DynamoCloudAccounts accounts;

    public DynamoAdmissionCleanupStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public DynamoAdmissionCleanupStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.accounts = new DynamoCloudAccounts(client, tables, clock); }

    @Override public int expire() {
        Instant now = accounts.now(), deadline = now.minus(CloudAdmissionCleanup.WAITING_EXPIRY);
        int removed = 0;
        for (var waiting : query(accounts.client(), accounts.accounts(), WAITING, "ACCOUNT#")) {
            Instant requestedAt = DynamoItems.instant(waiting, "requestedAt");
            if (!requestedAt.isBefore(deadline)) continue;
            UUID id = DynamoItems.uuid(waiting, "accountId");
            var control = accounts.control(id);
            if (control == null || control.admitted()) {
                // A projection left behind (an admission or removal that lost its race with a crash): drop it alone.
                var stale = accounts.transaction(); stale.delete(accounts.accounts(), key(WAITING, waitingSk(requestedAt, id)), null);
                try { stale.commit(); } catch (DynamoWrites.Conflict lost) { /* someone else did */ }
                continue;
            }
            // DLV-971: a waiting account the owner disabled keeps its state and its block; only untouched requests expire.
            if (!control.active()) continue;
            var tx = accounts.transaction();
            accounts.admission(tx, id, control.issuer(), control.subject(), null, "expire", List.of(), now);
            accounts.deleteWaiting(tx, control, accounts.identities(id));
            try { tx.commit(); } catch (DynamoWrites.Conflict lost) { continue; }
            removed++;
            Events.success(Events.EVENT, "admission.expired").addKeyValue("target.id", id)
                .log("Waiting account {} expired: not admitted within {} h, removed with its sign-in identities", id, CloudAdmissionCleanup.WAITING_EXPIRY.toHours());
        }
        int count = removed;
        Events.success(Events.EVENT, "admission.cleanup").addKeyValue("count", count).log("Admission cleanup removed {} expired waiting account(s)", count);
        return removed;
    }
}
