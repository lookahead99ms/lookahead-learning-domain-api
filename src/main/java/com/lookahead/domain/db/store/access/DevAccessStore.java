package com.lookahead.domain.db.store.access;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.signin.AccountItems;
import com.lookahead.domain.model.DevAccessModels.Row;
import com.lookahead.domain.model.DevAccessModels.Waiting;
import com.lookahead.domain.util.SignInRules;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: admission on DynamoDB. The waiting list is the {@code WAITING / ACCOUNT#requestedAt#id}
 * projection checked against each control item; the admission is one transaction conditioned on the
 * control revision read for the check and {@code admitted=false} (so admission and the 48-hour
 * cleanup are decided by one conditional write), with the waiting projection removed, the directory
 * updated and the insert-only admit event.
 */
@Repository
public class DevAccessStore implements DevAccess {
  private final AccountAggregateStore accounts;

  @Autowired
  public DevAccessStore(DynamoDbClient client, DynamoTableNames tables) {
    this(client, tables, Clock.systemUTC());
  }

  public DevAccessStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
    this.accounts = new AccountAggregateStore(client, tables, clock);
  }

  @Override
  public List<Waiting> waiting() {
    var rows = new ArrayList<Waiting>();
    for (var item :
        AccountItems.query(
            accounts.client(), accounts.accounts(), AccountItems.WAITING, "ACCOUNT#")) {
      UUID id = DynamoItems.uuid(item, "accountId");
      var control = accounts.control(id);
      // DLV-971: a waiting row the owner disabled (Reject) stays out of the list until it is
      // enabled again.
      if (control == null || !SignInRules.waitingListed(control.admitted(), control.active()))
        continue;
      Instant requested = control.requestedAt();
      rows.add(
          new Waiting(
              id,
              control.username(),
              control.displayName(),
              control.verifiedEmail(),
              requested,
              SignInRules.waitingExpires(requested),
              accounts.sameEmailAccounts(id, control.verifiedEmail()),
              accounts.signInMethods(id, false)));
    }
    return List.copyOf(rows);
  }

  @Override
  public Row lock(UUID accountId) {
    var control = accounts.control(accountId);
    return control == null
        ? null
        : new Row(
            accountId,
            control.username(),
            control.displayName(),
            control.issuer(),
            control.subject(),
            control.admitted(),
            control.stateLabel());
  }

  private record Read(AccountAggregateStore.Control control) implements Prepared {}

  @Override
  public Prepared prepare(Row row) {
    var control = accounts.control(row.accountId());
    return control == null ? null : new Read(control);
  }

  @Override
  public Instant admit(UUID actor, Prepared prepared, List<String> roles) {
    Instant now = accounts.now();
    var tx = accounts.transaction();
    accounts.admit(tx, ((Read) prepared).control(), actor, roles, now);
    tx.commit();
    return now;
  }
}
