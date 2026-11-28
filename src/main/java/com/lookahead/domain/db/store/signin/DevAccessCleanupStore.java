package com.lookahead.domain.db.store.signin;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.signin.AccountAggregateStore.Control;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the 48-hour cleanup on DynamoDB. The {@code WAITING / ACCOUNT#requestedAt#id} projection
 * lists the candidates; a still-waiting account is removed in one transaction conditioned on its
 * control revision and {@code admitted=false}, so an admission that lands first wins and a stale
 * cleanup removes nothing. Two tasks running at once simply split the work: a lost condition is
 * reported, never retried. DLV-1016: which account goes (the 48 hours, a disabled account kept) and
 * the run's log lines are the cleanup job's.
 */
public final class DevAccessCleanupStore implements DevAccessCleanup {
  /**
   * A row of the projection; its two values are parsed when asked for, in the order the job asks.
   */
  private record Row(Map<String, AttributeValue> item) implements Waiting {
    @Override
    public Instant requestedAt() {
      return DynamoItems.instant(item, "requestedAt");
    }

    @Override
    public UUID accountId() {
      return DynamoItems.uuid(item, "accountId");
    }
  }

  /** {@code control}: null when no account is behind the row. */
  private record Read(UUID id, Control control) implements Candidate {
    @Override
    public boolean exists() {
      return control != null;
    }

    @Override
    public boolean admitted() {
      return control.admitted();
    }

    @Override
    public boolean active() {
      return control.active();
    }
  }

  private final AccountAggregateStore accounts;

  public DevAccessCleanupStore(DynamoDbClient client, DynamoTableNames tables) {
    this(client, tables, Clock.systemUTC());
  }

  public DevAccessCleanupStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
    this.accounts = new AccountAggregateStore(client, tables, clock);
  }

  @Override
  public Instant now() {
    return accounts.now();
  }

  @Override
  public Iterable<Waiting> waiting() {
    var rows = query(accounts.client(), accounts.accounts(), WAITING, "ACCOUNT#");
    return () -> rows.stream().<Waiting>map(Row::new).iterator();
  }

  @Override
  public Candidate read(Waiting row) {
    UUID id = row.accountId();
    return new Read(id, accounts.control(id));
  }

  @Override
  public void dropRow(Waiting row) {
    var stale = accounts.transaction();
    stale.delete(
        accounts.accounts(), key(WAITING, waitingSk(row.requestedAt(), row.accountId())), null);
    try {
      stale.commit();
    } catch (DynamoWrites.Conflict lost) {
      /* someone else did */
    }
  }

  @Override
  public boolean expire(Candidate candidate, Instant now) {
    var read = (Read) candidate;
    Control control = read.control;
    UUID id = read.id;
    var tx = accounts.transaction();
    accounts.admission(tx, id, control.issuer(), control.subject(), null, "expire", List.of(), now);
    accounts.deleteWaiting(tx, control, accounts.identities(id));
    try {
      tx.commit();
    } catch (DynamoWrites.Conflict lost) {
      return false;
    }
    return true;
  }
}
