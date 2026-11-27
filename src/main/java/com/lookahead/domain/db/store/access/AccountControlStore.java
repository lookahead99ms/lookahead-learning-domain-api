package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.s;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.n;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.key;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * The V18 account state/intent transition on DynamoDB: one PartiQL transaction moves {@code ACCOUNT#id / CONTROL}
 * under its exact revision and generation, records the command receipt, the audit event and, for a removal, the
 * terminal marker and the erasure job. {@code AccountRemovalStore} drives it; the provider call and
 * the erasure worker are the caller's. A lost condition is {@link DynamoWrites.Conflict}, any other SDK failure
 * {@link DynamoWrites.Unavailable}, after the durable command receipt has been consulted.
 */
public final class AccountControlStore {
    public enum State { ACTIVE, DISABLED, REMOVED }
    public enum Action { DISABLE, ENABLE, REMOVE }
    /** Caller must construct from a verified principal; this class does not verify provider tokens. */
    public record Owner(UUID accountId, String issuer, String subject) {
        public Owner { Objects.requireNonNull(accountId); bounded(issuer, 2048); bounded(subject, 256); }
    }
    public record Command(Owner owner, UUID accountId, UUID operationId, long expectedRevision, Action action, Instant at) {
        public Command {
            Objects.requireNonNull(owner); Objects.requireNonNull(accountId); Objects.requireNonNull(operationId);
            Objects.requireNonNull(action); Objects.requireNonNull(at);
            if (expectedRevision < 1 || expectedRevision == Long.MAX_VALUE || at.isBefore(Instant.EPOCH)
                    || at.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new IllegalArgumentException("Invalid control revision/time");
        }
    }
    public record Control(UUID accountId, String issuer, String subject, String username, boolean admitted,
            boolean author, boolean managedTest, State state, long revision, long authGeneration) {}
    public record Result(Control current, long commandRevision, boolean replayed, boolean superseded) {}
    private record Snapshot(Control control, UUID removalId) {}
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ProtectedOwnerIdentityStore ownerIdentity;

    public AccountControlStore(DynamoDbClient client, DynamoTableNames tables) {
        this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables);
        this.ownerIdentity = new ProtectedOwnerIdentityStore(client, tables);
    }

    public Optional<Control> read(UUID id) { return Optional.ofNullable(snapshot(Objects.requireNonNull(id)).control()); }

    // Exception, decided 2026-11-25 (DLV-1016): this store checks a transition's preconditions itself (the owner identity, protected owner
    // and author accounts, "only admitted real accounts", "removal is final", the revision) and reports a failed one as a plain
    // IllegalStateException, never as an HTTP refusal. They stay here, as exceptions of exactly this type, for two measured reasons:
    // (1) the same checks run again in reconcile() on the control item re-read after the write and after a failed write, which no
    // service call sits between; (2) AccountRemovalStore catches IllegalStateException to tell "already in the wanted state" from a
    // refusal, and the line AccountRemovalService then writes ("Account … refused by the control store (…)") prints this exception's
    // type chain as error.type and its message, so a typed outcome or another exception type would change that log line.
    // AccountRemovalService answers the refusal (503 ACCOUNT_CONTROL_REFUSED); it has already refused author and managed test
    // accounts on its own earlier read, so for those this store is a second check.
    public Result transition(Command command) {
        Objects.requireNonNull(command);
        if (!ownerIdentity.matchesVerifiedIdentity(command.owner().issuer(), command.owner().subject())) {
            throw new IllegalStateException("Owner identity required");
        }
        var snapshot = snapshot(command.accountId());
        var current = snapshot.control();
        if (current == null) throw new IllegalStateException("Account control not found");
        protect(command, current);
        var previous = reconcile(command);
        if (previous.isPresent()) return previous.get();
        if (snapshot.removalId() != null) throw new IllegalStateException("Account already removed; recover its original operation");
        if (current.revision() != command.expectedRevision()) throw new IllegalStateException("Account control revision conflict");
        State next = switch (command.action()) { case DISABLE -> State.DISABLED; case ENABLE -> State.ACTIVE; case REMOVE -> State.REMOVED; };
        boolean changed = next != current.state();
        long generation = current.authGeneration();
        if (changed && next != State.ACTIVE) generation = Math.addExact(generation, 1);
        long revision = current.revision() + 1;
        var statements = new ArrayList<ParameterizedStatement>();
        statements.add(statement("UPDATE " + quoted(tables.userAccountDetailsTableName())
                + " SET accountState=? SET revision=? SET authGeneration=? SET changedAt=? SET changedBy=?"
                + " WHERE pk=? AND sk='CONTROL' AND schemaVersion=1 AND revision=? AND authGeneration=?"
                + " AND accountState=? AND issuer=? AND subject=? AND username=? AND admitted=true AND author=false AND managedTest=false",
                s(next.name()), n(revision), n(generation), s(command.at().toString()), s(command.owner().accountId().toString()),
                s(accountKey(command.accountId())), n(current.revision()), n(current.authGeneration()), s(current.state().name()),
                s(current.issuer()), s(current.subject()), s(current.username())));
        statements.add(statement("INSERT INTO " + quoted(tables.userAccountDetailsTableName())
                + " VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'fingerprint': ?, 'revision': ?, 'authGeneration': ?,"
                + " 'action': ?, 'issuer': ?, 'subject': ?, 'username': ?, 'providerStatus': ?}",
                s(commandKey(command.accountId())), s("OP#" + command.operationId()), s(fingerprint(command)), n(revision), n(generation),
                s(command.action().name()), s(current.issuer()), s(current.subject()), s(current.username()),
                s(next == State.REMOVED ? "AWAITING_ERASURE" : "PENDING")));
        if (changed) statements.add(statement("INSERT INTO " + quoted(tables.administratorAuditEventsTableName())
                + " VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'action': ?, 'actorId': ?, 'issuer': ?, 'subject': ?, 'occurredAt': ?}",
                s(accountKey(command.accountId())), s("ADMISSION#" + new DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(command.at()) + "#" + command.operationId()),
                s(command.action().name().toLowerCase(Locale.ROOT)), s(command.owner().accountId().toString()),
                s(current.issuer()), s(current.subject()), s(command.at().toString())));
        if (next == State.REMOVED) {
            statements.add(statement("INSERT INTO " + quoted(tables.administratorAuditEventsTableName())
                    + " VALUE {'pk': ?, 'sk': 'REMOVED', 'schemaVersion': 1, 'accountState': 'REMOVED', 'revision': ?,"
                    + " 'authGeneration': ?, 'issuer': ?, 'subject': ?, 'username': ?, 'admitted': true, 'author': false,"
                    + " 'managedTest': false, 'operationId': ?, 'changedAt': ?, 'changedBy': ?}",
                    s(accountKey(command.accountId())), n(revision), n(generation), s(current.issuer()), s(current.subject()),
                    s(current.username()), s(command.operationId().toString()), s(command.at().toString()), s(command.owner().accountId().toString())));
            statements.add(statement("INSERT INTO " + quoted(tables.userAccountDetailsTableName())
                    + " VALUE {'pk': ?, 'sk': 'JOB', 'schemaVersion': 1, 'operationId': ?, 'revision': ?, 'authGeneration': ?,"
                    + " 'issuer': ?, 'subject': ?, 'username': ?, 'phase': 'ERASE'}",
                    s("ACCOUNT_REMOVAL#" + command.accountId()), s(command.operationId().toString()), n(revision), n(generation),
                    s(current.issuer()), s(current.subject()), s(current.username())));
        }
        try {
            client.executeTransaction(ExecuteTransactionRequest.builder().transactStatements(statements).build());
        } catch (SdkException failure) {
            RuntimeException classified = DynamoWrites.classify(failure);
            try { var recovered = reconcile(command); if (recovered.isPresent()) return recovered.get(); }
            catch (RuntimeException reconciliationFailure) { classified.addSuppressed(reconciliationFailure); }
            throw classified;
        }
        Result saved = reconcile(command).orElseThrow(() -> new IllegalStateException("No durable account command after write"));
        return new Result(saved.current(), saved.commandRevision(), false, saved.superseded());
    }

    private Optional<Result> reconcile(Command command) {
        var item = DynamoItems.strongGet(client, tables.userAccountDetailsTableName(), commandKey(command.accountId()), "OP#" + command.operationId());
        if (item.isEmpty()) return Optional.empty();
        schema(item);
        if (!s(fingerprint(command)).equals(item.get("fingerprint"))) throw new IllegalStateException("Account command operation mismatch");
        long revision = number(item, "revision", 1), generation = number(item, "authGeneration", 0);
        if (revision != command.expectedRevision() + 1) throw new IllegalStateException("Invalid account command revision");
        var snapshot = snapshot(command.accountId()); var control = snapshot.control();
        if (control == null || control.revision() < revision || control.authGeneration() < generation) {
            throw new IllegalStateException("Control is missing or behind durable command");
        }
        protect(command, control);
        if (command.action() == Action.REMOVE && !command.operationId().equals(snapshot.removalId())) {
            throw new IllegalStateException("Remove command has no matching terminal marker");
        }
        return Optional.of(new Result(control, revision, true, control.revision() > revision));
    }

    private Snapshot snapshot(UUID id) {
        var response = client.transactGetItems(TransactGetItemsRequest.builder().transactItems(
                read(tables.userAccountDetailsTableName(), accountKey(id), "CONTROL"),
                read(tables.administratorAuditEventsTableName(), accountKey(id), "REMOVED")).build()).responses();
        if (response.size() != 2) throw new IllegalStateException("Incomplete account-control snapshot");
        var marker = response.get(1).item();
        if (!marker.isEmpty()) {
            var removed = parse(id, marker);
            if (removed.state() != State.REMOVED || !removed.admitted() || removed.author() || removed.managedTest()) {
                throw new IllegalStateException("Invalid terminal account marker");
            }
            return new Snapshot(removed, uuid(text(marker, "operationId")));
        }
        var control = response.getFirst().item();
        if (control.isEmpty()) return new Snapshot(null, null);
        var parsed = parse(id, control);
        if (parsed.state() == State.REMOVED) throw new IllegalStateException("Removed control has no terminal marker");
        return new Snapshot(parsed, null);
    }

    private static void protect(Command command, Control control) {
        if (command.accountId().equals(command.owner().accountId()) || control.author()
                || (control.issuer().equals(command.owner().issuer()) && control.subject().equals(command.owner().subject()))) {
            throw new IllegalStateException("Owner/author account protected");
        }
        if (!control.admitted() || control.managedTest()) throw new IllegalStateException("Only admitted real accounts can change here");
        if (control.state() == State.REMOVED && command.action() != Action.REMOVE) throw new IllegalStateException("Account removal is final");
    }
    private static Control parse(UUID id, Map<String, AttributeValue> item) {
        schema(item);
        String issuer=text(item,"issuer"), subject=text(item,"subject"), username=text(item,"username");
        bounded(issuer,2048); bounded(subject,256); bounded(username,256);
        return new Control(id,issuer,subject,username,bool(item,"admitted"),bool(item,"author"),bool(item,"managedTest"),
                State.valueOf(text(item,"accountState")),number(item,"revision",1),number(item,"authGeneration",0));
    }
    private static String fingerprint(Command c) {
        var value = new StringBuilder("account-control/v1");
        for (String part : List.of(c.owner().accountId().toString(),c.owner().issuer(),c.owner().subject(),c.accountId().toString(),
                c.operationId().toString(),Long.toString(c.expectedRevision()),c.action().name(),c.at().toString())) {
            value.append('|').append(part.getBytes(StandardCharsets.UTF_8).length).append(':').append(part);
        }
        return Sha256.hex(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void bounded(String value,int max) {
        if (value==null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length>max) throw new IllegalArgumentException("Invalid account identity field");
    }
    private static void schema(Map<String,AttributeValue> item) {if(!n(1).equals(item.get("schemaVersion")))throw new IllegalStateException("Invalid control schema");}
    private static String text(Map<String,AttributeValue> item,String key) {
        var value=item.get(key);if(value==null||value.s()==null)throw new IllegalStateException("Missing control text");return value.s();
    }
    private static boolean bool(Map<String,AttributeValue> item,String key) {
        var value=item.get(key);if(value==null||value.bool()==null)throw new IllegalStateException("Missing control flag");return value.bool();
    }
    private static long number(Map<String,AttributeValue> item,String key,long min) {
        try{long value=Long.parseLong(item.get(key).n());if(value<min)throw new IllegalArgumentException();return value;}
        catch(RuntimeException invalid){throw new IllegalStateException("Invalid control number",invalid);}
    }
    private static UUID uuid(String value) {
        UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalStateException("Noncanonical operation id");return id;
    }
    private static String accountKey(UUID id){return "ACCOUNT#"+id;}
    private static String commandKey(UUID id){return "ACCOUNT_COMMAND#"+id;}
    private static String quoted(String table){return '"'+table+'"';}
    private static TransactGetItem read(String table,String pk,String sk){return TransactGetItem.builder().get(Get.builder().tableName(table).key(key(pk,sk)).build()).build();}
    private static ParameterizedStatement statement(String sql,AttributeValue... args){return ParameterizedStatement.builder().statement(sql).parameters(args).build();}
}
