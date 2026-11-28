package com.lookahead.domain.health;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.dynamodb.DynamoDataRequirement;
import com.lookahead.domain.db.store.dynamodb.DynamoDataVersionStoreTest;
import com.lookahead.domain.db.store.dynamodb.DynamoReadiness;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNamesTest;
import com.lookahead.domain.log.HealthLog;
import com.lookahead.domain.log.LogCapture;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoReadinessIndicatorTest {
  private final DynamoDbClient client =
      mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
  private final DynamoTableNames tables = DynamoTableNamesTest.names("dev");
  private final DynamoDataRequirement requirement =
      new DynamoDataRequirement(List.of("base-tables", "plan-index"));
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-04T00:00:00Z"));
  private final Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
          return now.get();
        }
      };
  private final DynamoReadinessIndicator readiness =
      new DynamoReadinessIndicator(new DynamoReadiness(client, tables, requirement), clock);

  private void version(String version) {
    doAnswer(
            call -> {
              GetItemRequest request = call.getArgument(0);
              return GetItemResponse.builder()
                  .item(
                      request.tableName().equals(tables.protectedOwnerIdentityTableName())
                              && version != null
                          ? DynamoDataVersionStoreTest.head(version)
                          : Map.of())
                  .build();
            })
        .when(client)
        .getItem(any(GetItemRequest.class));
  }

  private void down(String reason) {
    try (var logs = LogCapture.start("lookahead.health")) {
      var result = readiness.health();
      assertThat(result.getStatus()).isEqualTo(Status.DOWN);
      assertThat(result.getDetails()).isEmpty();
      assertThat(LogCapture.keys(logs.only("readiness"))).containsEntry("reason", reason);
      assertThat(logs.only("readiness").getLevel()).isEqualTo(Level.ERROR);
      logs.containsNone("private-secret", "person@example.test", "Bearer");
    }
  }

  @Test
  void fourStrongBoundedReadsNoWritesNoContentsInHealthAndFutureVersionIsReady() {
    for (String version : List.of("2", "999")) {
      version(version);
      assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }
    var requests = ArgumentCaptor.forClass(GetItemRequest.class);
    verify(client, times(8)).getItem(requests.capture());
    for (var request : requests.getAllValues()) {
      assertThat(request.consistentRead()).isTrue();
      assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout())
          .contains(Duration.ofSeconds(2));
      assertThat(request.overrideConfiguration().orElseThrow().apiCallAttemptTimeout())
          .contains(Duration.ofSeconds(1));
      assertThat(request.projectionExpression())
          .isIn("pk", "pk, sk, #version, ledgerFormatVersion");
    }
    assertThat(requests.getAllValues().stream().map(GetItemRequest::tableName).distinct())
        .containsExactly(
            tables.userAccountDetailsTableName(),
            tables.administratorAuditEventsTableName(),
            tables.userStudyPlanDetailsTableName(),
            tables.protectedOwnerIdentityTableName());
    verifyNoMoreInteractions(client);
  }

  @Test
  void emptyBehindAndMalformedVersionsFailClosed() {
    version(null);
    down("DATA_VERSION_BEHIND");
    version("1");
    down("DATA_VERSION_BEHIND");
    version("private-secret");
    down("STORE_UNAVAILABLE");
    assertThat(HealthLog.last()).contains("dynamodb DOWN STORE_UNAVAILABLE");
  }

  @Test
  void logsOnChangeOncePerMinuteAndOnceOnRecoveryWithoutRepeatedUp() {
    version("1");
    try (var logs = LogCapture.start("lookahead.health")) {
      readiness.health();
      readiness.health();
      now.updateAndGet(t -> t.plusSeconds(59));
      readiness.health();
      assertThat(logs.lines("readiness")).hasSize(1);
      now.updateAndGet(t -> t.plusSeconds(1));
      readiness.health();
      version(null);
      readiness.health(); // Same reason, changed version details: log now.
      version("2");
      readiness.health();
      readiness.health();
      now.updateAndGet(t -> t.plusSeconds(120));
      readiness.health();
      assertThat(logs.lines("readiness"))
          .extracting(l -> l.getLevel())
          .containsExactly(Level.ERROR, Level.ERROR, Level.ERROR, Level.INFO);
      assertThat(logs.messages().getFirst())
          .startsWith("Readiness DOWN for dynamodb: ")
          .contains(
              "(DATA_VERSION_BEHIND); run 07 · Migrate DEV data with this release, then deploy"
                  + " again; the container health check fails until then");
      assertThat(logs.messages().getFirst())
          .contains(
              "data is at 1", "needs 2", "2 plan-index", "run the migration before deploying");
      assertThat(HealthLog.last()).contains("dynamodb UP");
    }
  }

  @Test
  void eachMissingTableIsIdentifiedWithoutReadingLaterTables() {
    var names =
        List.of(
            tables.userAccountDetailsTableName(),
            tables.administratorAuditEventsTableName(),
            tables.userStudyPlanDetailsTableName(),
            tables.protectedOwnerIdentityTableName());
    for (int index = 0; index < names.size(); index++) {
      String missing = names.get(index);
      clearInvocations(client);
      doAnswer(
              call -> {
                GetItemRequest r = call.getArgument(0);
                if (r.tableName().equals(missing))
                  throw ResourceNotFoundException.builder().message("private-secret").build();
                return GetItemResponse.builder().build();
              })
          .when(client)
          .getItem(any(GetItemRequest.class));
      down("TABLE_MISSING");
      verify(client, times(index + 1)).getItem(any(GetItemRequest.class));
      verifyNoMoreInteractions(client);
    }
  }

  @Test
  void permissionAndTransportFailuresAreClassifiedAndRedacted() {
    for (var failure :
        List.of(aws("AccessDeniedException", 400), aws("AccessDenied", 400), aws(null, 403))) {
      doThrow(failure).when(client).getItem(any(GetItemRequest.class));
      // Separate indicator keeps each assertion independent of reason-throttling.
      assertFailure("ACCESS_DENIED");
    }
    for (var failure : List.of(aws("ResourceNotFoundException", 400))) {
      doThrow(failure).when(client).getItem(any(GetItemRequest.class));
      assertFailure("TABLE_MISSING");
    }
    for (var failure :
        List.of(
            aws("ThrottlingException", 400),
            aws("private-secret", 500),
            DynamoDbException.builder().message("private-secret").build(),
            SdkClientException.create("Bearer private-secret"),
            new IllegalStateException("person@example.test"),
            new RuntimeException("private-secret") {})) {
      doThrow(failure).when(client).getItem(any(GetItemRequest.class));
      assertFailure("STORE_UNAVAILABLE");
    }
  }

  private void assertFailure(String reason) {
    try (var logs = LogCapture.start("lookahead.health")) {
      assertThat(
              new DynamoReadinessIndicator(new DynamoReadiness(client, tables, requirement), clock)
                  .health()
                  .getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(LogCapture.keys(logs.only("readiness"))).containsEntry("reason", reason);
      logs.containsNone("private-secret", "person@example.test", "Bearer");
    }
  }

  private RuntimeException aws(String code, int status) {
    var builder = DynamoDbException.builder();
    builder.statusCode(status).message("private-secret");
    builder.awsErrorDetails(
        AwsErrorDetails.builder().errorCode(code).errorMessage("person@example.test").build());
    return builder.build();
  }

  @Test
  void nullResponseAndNullDependenciesNeverReportUp() {
    when(client.getItem(any(GetItemRequest.class))).thenReturn(null);
    down("STORE_UNAVAILABLE");
    assertThatNullPointerException()
        .isThrownBy(() -> new DynamoReadiness(null, tables, requirement));
    assertThatNullPointerException()
        .isThrownBy(() -> new DynamoReadiness(client, null, requirement));
    assertThatNullPointerException().isThrownBy(() -> new DynamoReadiness(client, tables, null));
    assertThatNullPointerException().isThrownBy(() -> new DynamoReadinessIndicator(null, clock));
    assertThatNullPointerException()
        .isThrownBy(() ->
            new DynamoReadinessIndicator(new DynamoReadiness(client, tables, requirement), null));
  }
}
