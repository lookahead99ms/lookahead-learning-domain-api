package com.lookahead.domain.security.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * DLV-970: the DynamoDB store's failures answer exactly what the PostgreSQL ones do, without the
 * SDK's words in the answer. Plain logs (owner, 2026-11-17): the ERROR line does quote the SDK's
 * words (endpoint, request ID) and names the operation and table when the store knows them; the
 * stack trace follows with the reference.
 */
public class AccountErrorHandlerStorageFailureTest {
  public static final String SECRET =
      "synthetic endpoint http://127.0.0.1:4400 request-id=do-not-expose";
  final ListAppender<ILoggingEvent> logged = new ListAppender<>();
  final Boundary boundary = new Boundary();
  final MockMvc http =
      MockMvcBuilders.standaloneSetup(boundary)
          .setControllerAdvice(new AccountErrorHandler(), new GlobalExceptionHandler())
          .build();

  @RestController
  static class Boundary {
    Supplier<String> answer;

    @PostMapping("/api/v1/plans/dynamo")
    String save() {
      return answer.get();
    }
  }

  @BeforeEach
  void capture() {
    logged.start();
    ((Logger) LoggerFactory.getLogger("lookahead.failure")).addAppender(logged);
  }

  @AfterEach
  void release() {
    ((Logger) LoggerFactory.getLogger("lookahead.failure")).detachAppender(logged);
  }

  /** The only way to make the store's own exceptions: a transaction against a client that fails. */
  static RuntimeException fromStore(RuntimeException sdk) {
    var client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(sdk);
    try {
      DynamoWrites.transaction(client)
          .insertOnly("lookahead-dev-user-account-details", DynamoItems.item("A", "B", 1, Map.of()))
          .commit();
    } catch (RuntimeException failure) {
      return failure;
    }
    throw new AssertionError("expected a failure");
  }

  public static DynamoWrites.Conflict conflict() {
    return (DynamoWrites.Conflict)
        fromStore(
            TransactionCanceledException.builder()
                .cancellationReasons(
                    CancellationReason.builder()
                        .code("ConditionalCheckFailed")
                        .message(SECRET)
                        .build())
                .build());
  }

  public static DynamoWrites.Unavailable unavailable() {
    return (DynamoWrites.Unavailable)
        fromStore(SdkClientException.builder().message(SECRET).build());
  }

  /**
   * P2: a read that did not answer is an Unavailable too, so it is the same 503 and never a 500.
   */
  public static OwnerTransactionUnit.OwnerReadUnavailable readUnavailable() {
    var failure =
        new OwnerTransactionUnit.OwnerReadUnavailable(
            SdkClientException.builder().message(SECRET).build());
    assertThat(failure).isInstanceOf(DynamoWrites.Unavailable.class);
    return failure;
  }

  @Test
  void unknownOutcomeAndSdkFailuresAre503StorageUnavailableWithOneErrorLineQuotingTheSdk()
      throws Exception {
    for (RuntimeException failure :
        new RuntimeException[] {
          unavailable(),
          readUnavailable(),
          SdkClientException.builder().message(SECRET).build(),
          ProvisionedThroughputExceededException.builder().message(SECRET).build(),
          DynamoDbException.builder().message(SECRET).build()
        }) {
      logged.list.clear();
      boundary.answer =
          () -> {
            throw failure;
          };
      var result = http.perform(post("/api/v1/plans/dynamo")).andReturn();
      assertThat(result.getResponse().getStatus())
          .as(failure.getClass().getSimpleName())
          .isEqualTo(503);
      assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(result.getResponse().getContentAsString())
          .contains(
              "\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\"",
              "retain your draft",
              "retry the same request")
          .doesNotContain("4400", "request-id", "do-not-expose", "stackTrace");
      assertThat(logged.list)
          .singleElement()
          .satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage())
                .startsWith("POST /api/v1/plans/dynamo failed: DynamoDB ")
                .contains(
                    " failed (",
                    SECRET + ")",
                    "; answered 503 ACCOUNT_STORAGE_UNAVAILABLE; reference ");
            if (failure instanceof DynamoWrites.Unavailable store && !store.tables().isEmpty())
              assertThat(line.getFormattedMessage())
                  .contains(
                      "DynamoDB ExecuteTransaction on lookahead-dev-user-account-details failed"
                          + " (SdkClientException: "
                          + SECRET
                          + ")");
            assertThat(line.getThrowableProxy())
                .as("the stack trace follows the ERROR line")
                .isNotNull();
          });
    }
  }

  @Test
  void aConditionThatDidNotHoldIsTheExistingRevisionConflictAnswer() throws Exception {
    boundary.answer =
        () -> {
          throw conflict();
        };
    var result = http.perform(post("/api/v1/plans/dynamo")).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getContentAsString())
        .contains("\"code\":\"REVISION_CONFLICT\"", "reload before retrying")
        .doesNotContain("do-not-expose", "currentRevision");
    assertThat(logged.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .isEqualTo("Account storage conflict: POST /api/v1/plans/dynamo (Conflict)");
  }

  @Test
  void anUnrelatedFailureStays500() throws Exception {
    boundary.answer =
        () -> {
          throw new IllegalStateException("synthetic programming detail");
        };
    var result = http.perform(post("/api/v1/plans/dynamo")).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(500);
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("ACCOUNT_STORAGE_UNAVAILABLE", "REVISION_CONFLICT");
  }
}
