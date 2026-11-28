package com.lookahead.domain.db.store.dynamodb;

import com.lookahead.domain.exception.StorageFailure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest;
import software.amazon.awssdk.services.dynamodb.model.ParameterizedStatement;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * DLV-970: one conditional transaction of PartiQL statements ({@code ExecuteTransaction}) with the
 * outcomes an adapter has to tell apart. PartiQL, not {@code TransactWriteItems}: the runtime
 * role's permission on the Administrator Audit Events table is {@code dynamodb:PartiQLInsert} only
 * (approved F1 design), so every audit event is an {@code INSERT} that fails on an existing key,
 * and a transaction that carries one must be PartiQL throughout. A failed condition or duplicate
 * key is a {@link Conflict} (the adapter re-reads and retries, or reports the existing business
 * conflict); any other SDK failure is {@link Unavailable} (outcome unknown: the caller reconciles
 * through a durable receipt before retrying anything with a side effect). At most 100 statements;
 * each statement addresses one item by its full key.
 *
 * <p>Statement forms, with {@code ?} parameters bound in order: {@code INSERT INTO "t" VALUE {'pk':
 * ?, …}} · {@code UPDATE "t" SET "a"=? SET "b"=? WHERE pk=? AND sk=? AND …} (attribute names in SET
 * are double-quoted, so reserved words such as {@code old} or {@code name} are safe; quote them the
 * same way in your own WHERE text) · {@code DELETE FROM "t" WHERE pk=? AND sk=? AND …}. An {@code
 * UPDATE} or {@code DELETE} whose WHERE matches nothing (missing item or failed condition) cancels
 * the transaction. There is no REMOVE (DynamoDB Local rejects it): to clear an attribute, set it to
 * {@link DynamoItems#nul()}; the readers treat NULL as absent.
 */
public final class DynamoWrites {
  public static final int MAX_ITEMS = 100;

  /**
   * A condition did not hold or a key already existed: another writer moved first, or the expected
   * state is not there.
   */
  public static final class Conflict extends StorageFailure {
    private final List<String> reasons;

    @Override
    public boolean conflict() {
      return true;
    }

    public Conflict(List<String> reasons) {
      super("DynamoDB transaction conflict: " + String.join(", ", reasons));
      this.reasons = List.copyOf(reasons);
    }

    /**
     * One code per statement in request order: None, ConditionalCheckFailed, DuplicateItem,
     * TransactionConflict, …
     */
    public List<String> reasons() {
      return reasons;
    }

    /** True when exactly the given statement (0-based) failed and nothing else did. */
    public boolean only(int index) {
      if (index >= reasons.size()) return false;
      for (int at = 0; at < reasons.size(); at++)
        if (reasons.get(at).equals("None") == (at == index)) return false;
      return true;
    }
  }

  /**
   * The store did not answer, or answered with something other than a condition failure: outcome
   * unknown. Open for the read-side twin ({@code OwnerTransactionUnit.OwnerReadUnavailable}) so one
   * handler answers 503 for both.
   */
  public static class Unavailable extends StorageFailure {
    private final String operation;
    private final List<String> tables;

    @Override
    public final boolean conflict() {
      return false;
    }

    Unavailable(SdkException cause, String operation, List<String> tables) {
      this(
          "DynamoDB write unavailable: " + cause.getClass().getSimpleName(),
          cause,
          operation,
          tables);
    }

    protected Unavailable(String message, SdkException cause) {
      this(message, cause, "read", List.of());
    }

    /**
     * Plain logs (owner, 2026-11-17): the operation and the table(s) addressed, so the ERROR line
     * can name them.
     */
    protected Unavailable(
        String message, SdkException cause, String operation, List<String> tables) {
      super(message, cause);
      this.operation = operation == null ? "call" : operation;
      this.tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /**
     * The table name(s) the operation addressed, in statement order without repeats; empty when not
     * known.
     */
    public List<String> tables() {
      return tables;
    }

    /**
     * "DynamoDB ExecuteTransaction on lookahead-dev-user-account-details" (the sentence's
     * dependency, operation and resource).
     */
    public String call() {
      return "DynamoDB " + operation + (tables.isEmpty() ? "" : " on " + String.join(", ", tables));
    }
  }

  private final DynamoDbClient client;
  private final List<ParameterizedStatement> statements = new ArrayList<>();

  private DynamoWrites(DynamoDbClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  public static DynamoWrites transaction(DynamoDbClient client) {
    return new DynamoWrites(client);
  }

  public int size() {
    return statements.size();
  }

  /** For tests and diagnostics: the statement texts in order (never the bound values). */
  public List<String> statements() {
    return statements.stream().map(ParameterizedStatement::statement).toList();
  }

  /** INSERT: the key must be absent. The only way an administrator audit event is written. */
  public DynamoWrites insertOnly(String table, Map<String, AttributeValue> item) {
    if (!item.containsKey("pk") || !item.containsKey("sk"))
      throw new IllegalArgumentException("An item needs pk and sk");
    var names = new ArrayList<String>();
    var values = new ArrayList<AttributeValue>();
    for (var entry : new TreeMap<>(item).entrySet()) {
      names.add("'" + attribute(entry.getKey()) + "': ?");
      values.add(entry.getValue());
    }
    return add(
        "INSERT INTO " + quoted(table) + " VALUE {" + String.join(", ", names) + "}", values);
  }

  /**
   * UPDATE SET each attribute of {@code set} WHERE the key matches and {@code where} holds. {@code
   * where} is PartiQL with {@code ?} placeholders, for example {@code "revision=? AND
   * accountState=?"}; it may be null for a key-only update. The item must exist: a missing item
   * cancels the transaction.
   */
  public DynamoWrites update(
      String table,
      Map<String, AttributeValue> key,
      Map<String, AttributeValue> set,
      String where,
      AttributeValue... whereParameters) {
    requireKey(key);
    if (set == null || set.isEmpty())
      throw new IllegalArgumentException("An update needs something to set");
    var text = new StringBuilder("UPDATE " + quoted(table));
    var values = new ArrayList<AttributeValue>();
    for (var entry : new TreeMap<>(set).entrySet()) {
      if (entry.getKey().equals("pk") || entry.getKey().equals("sk"))
        throw new IllegalArgumentException("Key attributes cannot be updated");
      text.append(" SET \"").append(attribute(entry.getKey())).append("\"=?");
      values.add(Objects.requireNonNull(entry.getValue(), entry.getKey()));
    }
    text.append(" WHERE pk=? AND sk=?");
    values.add(key.get("pk"));
    values.add(key.get("sk"));
    appendWhere(text, values, where, whereParameters);
    return add(text.toString(), values);
  }

  /**
   * UPDATE under an exact revision: {@code set} must carry {@code revision = expectedRevision + 1}.
   */
  public DynamoWrites updateAtRevision(
      String table,
      Map<String, AttributeValue> key,
      Map<String, AttributeValue> set,
      long expectedRevision) {
    if (!set.containsKey("revision") || DynamoItems.number(set, "revision") != expectedRevision + 1)
      throw new IllegalArgumentException("Update must carry revision + 1");
    return update(table, key, set, "revision=?", DynamoItems.n(expectedRevision));
  }

  /**
   * DELETE WHERE the key matches and {@code where} holds. A missing item is a silent no-op whatever
   * the condition says (DynamoDB semantics: the WHERE only fences an existing item), which makes
   * deletes naturally idempotent. A transaction may touch one item once, so "delete only if
   * present" is modelled as a fenced update to a tombstone state, never as check + delete on the
   * same key.
   */
  public DynamoWrites delete(
      String table,
      Map<String, AttributeValue> key,
      String where,
      AttributeValue... whereParameters) {
    requireKey(key);
    var text = new StringBuilder("DELETE FROM " + quoted(table) + " WHERE pk=? AND sk=?");
    var values = new ArrayList<AttributeValue>(List.of(key.get("pk"), key.get("sk")));
    appendWhere(text, values, where, whereParameters);
    return add(text.toString(), values);
  }

  /**
   * A condition-only statement on an existing item: the transaction fails unless the item exists
   * with {@code schemaVersion = schema} and {@code where} holds. Implemented as an UPDATE that
   * writes the same schema version back, since PartiQL has no bare condition check.
   */
  public DynamoWrites check(
      String table,
      Map<String, AttributeValue> key,
      int schema,
      String where,
      AttributeValue... whereParameters) {
    requireKey(key);
    var text =
        new StringBuilder(
            "UPDATE "
                + quoted(table)
                + " SET schemaVersion=? WHERE pk=? AND sk=? AND schemaVersion=?");
    var values =
        new ArrayList<AttributeValue>(
            List.of(DynamoItems.n(schema), key.get("pk"), key.get("sk"), DynamoItems.n(schema)));
    appendWhere(text, values, where, whereParameters);
    return add(text.toString(), values);
  }

  private static void appendWhere(
      StringBuilder text, List<AttributeValue> values, String where, AttributeValue[] parameters) {
    int expected = where == null ? 0 : where.length() - where.replace("?", "").length();
    if (parameters.length != expected)
      throw new IllegalArgumentException("WHERE placeholders and parameters differ");
    if (where == null || where.isBlank()) return;
    if (where.contains(";") || where.contains("--"))
      throw new IllegalArgumentException("Invalid WHERE clause");
    text.append(" AND ").append(where);
    values.addAll(List.of(parameters));
  }

  private DynamoWrites add(String text, List<AttributeValue> values) {
    if (statements.size() >= MAX_ITEMS)
      throw new IllegalStateException(
          "A DynamoDB transaction holds at most " + MAX_ITEMS + " statements");
    statements.add(ParameterizedStatement.builder().statement(text).parameters(values).build());
    return this;
  }

  private static void requireKey(Map<String, AttributeValue> key) {
    if (key == null || key.size() != 2 || key.get("pk") == null || key.get("sk") == null)
      throw new IllegalArgumentException("A key is pk and sk");
  }

  static String attribute(String name) {
    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
      throw new IllegalArgumentException("Invalid attribute name");
    return name;
  }

  static String quoted(String table) {
    if (table == null || !table.matches("[A-Za-z0-9_.-]{1,255}"))
      throw new IllegalArgumentException("Invalid table name");
    return '"' + table + '"';
  }

  /**
   * Commits all statements or none. {@code token}, when given, is the SDK's short-term idempotency
   * guard, not a receipt.
   */
  public void commit(String token) {
    if (statements.isEmpty()) throw new IllegalStateException("Nothing to commit");
    var request = ExecuteTransactionRequest.builder().transactStatements(statements);
    if (token != null) request.clientRequestToken(token);
    try {
      client.executeTransaction(request.build());
    } catch (SdkException failure) {
      throw classify(failure, tables());
    }
  }

  /**
   * The table names the statements address, in order without repeats (from the quoted name after
   * INSERT INTO, UPDATE, DELETE FROM).
   */
  List<String> tables() {
    var names = new LinkedHashSet<String>();
    for (var statement : statements) {
      var found = TABLE_NAME.matcher(statement.statement());
      if (found.find()) names.add(found.group(1));
    }
    return List.copyOf(names);
  }

  private static final Pattern TABLE_NAME =
      Pattern.compile("^(?:INSERT INTO|UPDATE|DELETE FROM) \"([A-Za-z0-9_.-]+)\"");

  /**
   * The one mapping from an SDK failure to the store's outcomes, for every library that sends its
   * own {@code ExecuteTransaction}: a cancellation whose reasons include ConditionalCheckFailed,
   * DuplicateItem or TransactionConflict (or a bare ConditionalCheckFailedException) is a {@link
   * Conflict}, which the web layer answers 409; anything else is {@link Unavailable} (503, outcome
   * unknown) with the SDK exception as its cause (the web layer's ERROR line quotes its message). A
   * Conflict carries the cancellation reasons and no cause: it is a business outcome.
   */
  public static RuntimeException classify(SdkException failure) {
    return classify(failure, List.of());
  }

  /**
   * As above, with the table name(s) the transaction addressed, which an {@link Unavailable}
   * carries for the ERROR line.
   */
  public static RuntimeException classify(SdkException failure, List<String> tables) {
    if (failure instanceof TransactionCanceledException cancelled) {
      var reasons =
          cancelled.cancellationReasons() == null
              ? List.<CancellationReason>of()
              : cancelled.cancellationReasons();
      var codes = reasons.stream().map(DynamoWrites::code).toList();
      boolean conditional =
          codes.stream()
              .anyMatch(code ->
                  code.equals("ConditionalCheckFailed")
                      || code.equals("DuplicateItem")
                      || code.equals("TransactionConflict"));
      if (!conditional) return new Unavailable(cancelled, "ExecuteTransaction", tables);
      return new Conflict(codes);
    }
    if (failure instanceof ConditionalCheckFailedException)
      return new Conflict(List.of("ConditionalCheckFailed"));
    return new Unavailable(failure, "ExecuteTransaction", tables);
  }

  public void commit() {
    commit(null);
  }

  /**
   * An INSERT on an existing key is reported as a ValidationError "Duplicate primary key…"
   * (DynamoDB Local) or DuplicateItem.
   */
  static String code(CancellationReason reason) {
    if (reason.code() == null) return "None";
    if ("ValidationError".equals(reason.code())
        && reason.message() != null
        && reason.message().toLowerCase(Locale.ROOT).contains("duplicate")) return "DuplicateItem";
    return reason.code();
  }
}
