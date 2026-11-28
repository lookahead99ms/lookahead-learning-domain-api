package com.lookahead.domain.db.store.dynamodb;

import java.time.Clock;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the {@code PlatformTransactionManager} of the plan stores. Begin binds a fresh {@link
 * OwnerTransactionUnit} to the thread, commit sends it as one conditional transaction, rollback
 * discards it. StudyPlanService's existing {@code @Transactional} boundaries therefore stay exactly
 * where they are.
 */
public class OwnerTransactionManager extends AbstractPlatformTransactionManager {
  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final Clock clock;

  public OwnerTransactionManager(DynamoDbClient client, DynamoTableNames tables) {
    this(client, tables, Clock.systemUTC());
  }

  public OwnerTransactionManager(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
    this.client = client;
    this.tables = tables;
    this.clock = clock;
  }

  static final class Holder {
    OwnerTransactionUnit unit;
  }

  @Override
  protected Object doGetTransaction() {
    var holder = new Holder();
    holder.unit =
        (OwnerTransactionUnit)
            TransactionSynchronizationManager.getResource(OwnerTransactionUnit.RESOURCE_KEY);
    return holder;
  }

  @Override
  protected boolean isExistingTransaction(Object transaction) {
    return ((Holder) transaction).unit != null;
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    var unit = new OwnerTransactionUnit(client, tables, clock);
    ((Holder) transaction).unit = unit;
    TransactionSynchronizationManager.bindResource(OwnerTransactionUnit.RESOURCE_KEY, unit);
  }

  @Override
  protected Object doSuspend(Object transaction) {
    ((Holder) transaction).unit = null;
    return TransactionSynchronizationManager.unbindResource(OwnerTransactionUnit.RESOURCE_KEY);
  }

  @Override
  protected void doResume(Object transaction, Object suspended) {
    TransactionSynchronizationManager.bindResource(OwnerTransactionUnit.RESOURCE_KEY, suspended);
  }

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    ((Holder) status.getTransaction()).unit.commit();
  }

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    /* nothing was sent: the unit is simply dropped */
  }

  @Override
  protected void doCleanupAfterCompletion(Object transaction) {
    if (TransactionSynchronizationManager.hasResource(OwnerTransactionUnit.RESOURCE_KEY))
      TransactionSynchronizationManager.unbindResource(OwnerTransactionUnit.RESOURCE_KEY);
  }
}
