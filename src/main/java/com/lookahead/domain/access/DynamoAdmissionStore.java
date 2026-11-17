package com.lookahead.domain.access;

import com.lookahead.domain.access.AccountAdmissionService.Row;
import com.lookahead.domain.access.AccountAdmissionService.Waiting;
import com.lookahead.domain.cloud.CloudAccountItems;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: admission on DynamoDB. The waiting list is the {@code WAITING / ACCOUNT#requestedAt#id} projection checked
 * against each control item; the admission is one transaction conditioned on the control revision read for the check
 * and {@code admitted=false} (so admission and the 48-hour cleanup are decided by one conditional write), with the
 * waiting projection removed, the directory updated and the insert-only admit event.
 */
@Repository
public class DynamoAdmissionStore implements AdmissionStore {
    private final DynamoCloudAccounts accounts;

    @Autowired
    public DynamoAdmissionStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public DynamoAdmissionStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.accounts = new DynamoCloudAccounts(client, tables, clock); }

    @Override public List<Waiting> waiting() {
        var rows = new ArrayList<Waiting>();
        for (var item : CloudAccountItems.query(accounts.client(), accounts.accounts(), CloudAccountItems.WAITING, "ACCOUNT#")) {
            UUID id = DynamoItems.uuid(item, "accountId");
            var control = accounts.control(id);
            // DLV-971: a waiting row the owner disabled (Reject) stays out of the list until it is enabled again.
            if (control == null || control.admitted() || !control.active()) continue;
            Instant requested = control.requestedAt();
            rows.add(new Waiting(id, control.username(), control.displayName(), control.verifiedEmail(), requested, requested.plus(CloudAdmissionCleanup.WAITING_EXPIRY),
                    accounts.sameEmailAccounts(id, control.verifiedEmail()), accounts.signInMethods(id, false)));
        }
        return List.copyOf(rows);
    }
    @Override public Row lock(UUID accountId) {
        var control = accounts.control(accountId);
        return control == null ? null : new Row(accountId, control.username(), control.displayName(), control.issuer(), control.subject(), control.admitted(), control.sqlState());
    }
    @Override public Instant admit(UUID actor, Row row, List<String> roles, Runnable assignRoles) {
        var control = accounts.control(row.accountId());
        if (control == null) throw new AccountFailure(404, "ACCOUNT_NOT_FOUND", "No account with that id is waiting.");
        // Roles first: a staff assignment on a still-waiting account is harmless, an admitted account without its roles is not.
        assignRoles.run();
        Instant now = accounts.now();
        var tx = accounts.transaction();
        accounts.admit(tx, control, actor, roles, now);
        tx.commit();
        return now;
    }
}
