package com.lookahead.domain.db.store.access;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNamesTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * DLV-970: the verifier reads by key only (counter, marker, the claim at the high-water mark, its
 * alias, the next claim); never a Scan.
 */
class StaffAliasVerificationStoreTest {
  private static final List<String> ROLES =
      List.of("co-author", "admin", "reviewer", "tester", "learner");
  private final DynamoDbClient client =
      mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
  private final DynamoTableNames tables = DynamoTableNamesTest.names("dev");
  private final StaffAliasVerificationStore verifier =
      new StaffAliasVerificationStore(client, tables);
  private final String id = UUID.randomUUID().toString();
  private final String account = UUID.randomUUID().toString();
  private final String time = "2026-11-03T12:00:00Z";

  /** The store: table|pk|sk → item. */
  private final Map<String, Map<String, AttributeValue>> store = new HashMap<>();

  @Test
  void nullDependenciesAreRefusedBeforeIo() {
    assertThatNullPointerException()
        .isThrownBy(() -> new StaffAliasVerificationStore(null, tables));
    assertThatNullPointerException()
        .isThrownBy(() -> new StaffAliasVerificationStore(client, null));
    verifyNoInteractions(client);
  }

  @Test
  void anInitializedEmptyNamespaceNeedsFifteenStrongKeyReadsAndNoScan() {
    for (String role : ROLES) seed(role, 0);
    serve();
    var result = verifier.verify();
    assertThat(result.assigned()).isZero();
    assertThat(result.assignedPerRole()).containsOnlyKeys(ROLES).containsValues(0L);
    assertThatThrownBy(() -> result.assignedPerRole().put("admin", 9L))
        .isInstanceOf(UnsupportedOperationException.class);
    // Counter, marker and the claim the next allocation would mint (a counter rolled back behind a
    // retained claim), per role.
    var captor = ArgumentCaptor.forClass(GetItemRequest.class);
    verify(client, times(15)).getItem(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(request -> assertThat(request.consistentRead()).isTrue());
    assertThat(captor.getAllValues())
        .extracting(GetItemRequest::tableName)
        .containsOnly(
            tables.userAccountDetailsTableName(), tables.administratorAuditEventsTableName());
    verify(client, never()).scan(any(ScanRequest.class));
    verify(client, never()).query(any(QueryRequest.class));
    verifyNoMoreInteractions(client);
  }

  @Test
  void anAssignedRoleChecksTheClaimAtTheHighWaterMarkItsAliasAndTheNextNumber() {
    for (String role : ROLES) seed(role, 0);
    seed("admin", 3);
    claim("admin", 3);
    alias("admin", 3);
    serve();
    var result = verifier.verify();
    assertThat(result.assigned()).isEqualTo(3);
    assertThat(result.assignedPerRole()).containsEntry("admin", 3L).containsEntry("learner", 0L);
    verify(client, times(4 * 3 + 5)).getItem(any(GetItemRequest.class));
    verify(client, never()).scan(any(ScanRequest.class));
  }

  @Test
  void counterAheadBehindOrDetachedFromItsClaimsFailsClosed() {
    for (String role : ROLES) seed(role, 0);
    seed("admin", 3);
    serve();
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Counter is ahead of its number claims");
    claim("admin", 3);
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Number claim has no matching alias");
    alias("admin", 2);
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Alias does not match its number claim");
    alias("admin", 3);
    var late =
        new HashMap<>(
            store.get(
                key(
                    tables.administratorAuditEventsTableName(),
                    "STAFF_ALIAS#" + account,
                    "ROLE#admin")));
    late.put("assignedAt", s("2026-11-03T12:00:01Z"));
    store.put(
        key(tables.administratorAuditEventsTableName(), "STAFF_ALIAS#" + account, "ROLE#admin"),
        late);
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Alias does not match its number claim");
    alias("admin", 3);
    assertThat(verifier.verify().assigned()).isEqualTo(3);
    claim("admin", 4);
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Counter is behind assigned number");
  }

  @Test
  void missingOrMismatchedCountersAndMarkersFailClosed() {
    for (String role : ROLES) seed(role, 0);
    serve();
    store.remove(key(tables.userAccountDetailsTableName(), "STAFF_ROLE#tester", "COUNTER"));
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Missing role counter");
    seed("tester", 0);
    store.remove(
        key(tables.administratorAuditEventsTableName(), "STAFF_ROLE#tester", "INITIALIZED"));
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Missing initialization marker");
    store.put(
        key(tables.administratorAuditEventsTableName(), "STAFF_ROLE#tester", "INITIALIZED"),
        Map.of("schemaVersion", n(1), "initializationId", s(UUID.randomUUID().toString())));
    assertThatIllegalStateException()
        .isThrownBy(verifier::verify)
        .withMessage("Mismatched initialization marker");
  }

  @Test
  void malformedCounterClaimAndAliasRecordsFailClosedWithoutEchoingIdentity() {
    for (String role : ROLES) seed(role, 0);
    seed("admin", 1);
    claim("admin", 1);
    alias("admin", 1);
    serve();
    String counter = key(tables.userAccountDetailsTableName(), "STAFF_ROLE#admin", "COUNTER");
    for (String field : List.of("schemaVersion", "lastAssigned", "revision", "initializationId"))
      assertThatIllegalStateException()
          .isThrownBy(() -> {
            var item = new HashMap<>(store.get(counter));
            item.remove(field);
            withStore(counter, item, verifier::verify);
          });
    for (var change :
        List.of(
            Map.entry("lastAssigned", n(-1)),
            Map.entry("lastAssigned", n(2147483648L)),
            Map.entry("revision", s("bad")),
            Map.entry("initializationId", s("invalid")),
            Map.entry("initializationId", s(id.toUpperCase(Locale.ROOT)))))
      assertThatIllegalStateException()
          .isThrownBy(() ->
              withStore(
                  counter,
                  changed(store.get(counter), change.getKey(), change.getValue()),
                  verifier::verify))
          .satisfies(error -> assertThat(error.getMessage()).doesNotContain(id, account));
    String claim =
        key(tables.administratorAuditEventsTableName(), "STAFF_ROLE#admin", "NUMBER#0000000001");
    for (var change :
        List.of(
            Map.entry("schemaVersion", n(2)),
            Map.entry("accountId", s("bad")),
            Map.entry("assignedAt", s("2026-11-03T12:00:00.000Z")),
            Map.entry("assignedAt", s("1969-01-01T00:00:00Z"))))
      assertThatIllegalStateException()
          .isThrownBy(() ->
              withStore(
                  claim,
                  changed(store.get(claim), change.getKey(), change.getValue()),
                  verifier::verify))
          .satisfies(error -> assertThat(error.getMessage()).doesNotContain(id, account));
    String alias =
        key(tables.administratorAuditEventsTableName(), "STAFF_ALIAS#" + account, "ROLE#admin");
    for (var change :
        List.of(
            Map.entry("schemaVersion", n(2)),
            Map.entry("number", n(0)),
            Map.entry("number", s("x")),
            Map.entry("assignedAt", s("bad"))))
      assertThatIllegalStateException()
          .isThrownBy(() ->
              withStore(
                  alias,
                  changed(store.get(alias), change.getKey(), change.getValue()),
                  verifier::verify));
    assertThat(verifier.verify().assigned()).isEqualTo(1);
  }

  @Test
  void anExhaustedCounterIsIntactWithoutReadingPastTheLastNumber() {
    for (String role : ROLES) seed(role, 0);
    seed("admin", Integer.MAX_VALUE);
    claim("admin", Integer.MAX_VALUE);
    alias("admin", Integer.MAX_VALUE);
    serve();
    assertThat(verifier.verify().assignedPerRole())
        .containsEntry("admin", (long) Integer.MAX_VALUE);
    verify(client, times(4 * 3 + 4)).getItem(any(GetItemRequest.class));
  }

  private void withStore(String key, Map<String, AttributeValue> item, Runnable action) {
    var original = store.get(key);
    store.put(key, item);
    try {
      action.run();
    } finally {
      store.put(key, original);
    }
  }

  private void serve() {
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(call -> {
          GetItemRequest request = call.getArgument(0);
          var item =
              store.get(
                  key(
                      request.tableName(),
                      request.key().get("pk").s(),
                      request.key().get("sk").s()));
          return GetItemResponse.builder().item(item == null ? Map.of() : item).build();
        });
  }

  private void seed(String role, long last) {
    store.put(
        key(tables.userAccountDetailsTableName(), "STAFF_ROLE#" + role, "COUNTER"),
        Map.of(
            "schemaVersion",
            n(1),
            "lastAssigned",
            n(last),
            "revision",
            n(last),
            "initializationId",
            s(id)));
    store.put(
        key(tables.administratorAuditEventsTableName(), "STAFF_ROLE#" + role, "INITIALIZED"),
        Map.of("schemaVersion", n(1), "initializationId", s(id)));
  }

  private void claim(String role, long number) {
    store.put(
        key(
            tables.administratorAuditEventsTableName(),
            "STAFF_ROLE#" + role,
            "NUMBER#" + String.format(Locale.ROOT, "%010d", number)),
        Map.of("schemaVersion", n(1), "accountId", s(account), "assignedAt", s(time)));
  }

  private void alias(String role, long number) {
    store.put(
        key(tables.administratorAuditEventsTableName(), "STAFF_ALIAS#" + account, "ROLE#" + role),
        Map.of("schemaVersion", n(1), "number", n(number), "assignedAt", s(time)));
  }

  private static String key(String table, String pk, String sk) {
    return table + "|" + pk + "|" + sk;
  }

  private Map<String, AttributeValue> changed(
      Map<String, AttributeValue> base, String key, AttributeValue value) {
    var result = new HashMap<>(base);
    result.put(key, value);
    return result;
  }

  private static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue n(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }
}
