package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the {@code PlatformTransactionManager} of dynamodb mode. Begin binds a fresh {@link DynamoOwnerUnit} to the
 * thread, commit sends it as one conditional transaction, rollback discards it. PlanService's existing
 * {@code @Transactional} boundaries therefore stay exactly where they are in both stores.
 */
public class DynamoOwnerTransactionManager extends AbstractPlatformTransactionManager {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    public DynamoOwnerTransactionManager(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    DynamoOwnerTransactionManager(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = client; this.tables = tables; this.clock = clock;
    }

    static final class Holder { DynamoOwnerUnit unit; }

    @Override protected Object doGetTransaction() {
        var holder = new Holder();
        holder.unit = (DynamoOwnerUnit) TransactionSynchronizationManager.getResource(DynamoOwnerUnit.RESOURCE_KEY);
        return holder;
    }
    @Override protected boolean isExistingTransaction(Object transaction) { return ((Holder) transaction).unit != null; }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
        var unit = new DynamoOwnerUnit(client, tables, clock);
        ((Holder) transaction).unit = unit;
        TransactionSynchronizationManager.bindResource(DynamoOwnerUnit.RESOURCE_KEY, unit);
    }
    @Override protected Object doSuspend(Object transaction) {
        ((Holder) transaction).unit = null;
        return TransactionSynchronizationManager.unbindResource(DynamoOwnerUnit.RESOURCE_KEY);
    }
    @Override protected void doResume(Object transaction, Object suspended) {
        TransactionSynchronizationManager.bindResource(DynamoOwnerUnit.RESOURCE_KEY, suspended);
    }
    @Override protected void doCommit(DefaultTransactionStatus status) {
        ((Holder) status.getTransaction()).unit.commit();
    }
    @Override protected void doRollback(DefaultTransactionStatus status) { /* nothing was sent: the unit is simply dropped */ }
    @Override protected void doCleanupAfterCompletion(Object transaction) {
        if (TransactionSynchronizationManager.hasResource(DynamoOwnerUnit.RESOURCE_KEY)) TransactionSynchronizationManager.unbindResource(DynamoOwnerUnit.RESOURCE_KEY);
    }
}
