package com.lookahead.domain.db.store.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

/**
 * DLV-1016: which failures mean "DynamoDB did not answer"; exactly the types the storage failure
 * filter caught by name before.
 */
class DynamoFailuresTest {
  static final SdkClientException NO_CONNECTION =
      SdkClientException.builder().message("synthetic").build();

  @Test
  void theStoresOutageAndTheSdksOwnFailuresAreUnavailable() {
    // The second is a subclass, as the read-side failure of the stores is.
    for (Throwable failure :
        List.of(
            new DynamoWrites.Unavailable(NO_CONNECTION, "ExecuteTransaction", List.of()),
            new DynamoWrites.Unavailable(
                "DynamoDB read unavailable: SdkClientException",
                NO_CONNECTION,
                "GetItem",
                List.of("synthetic-table")) {},
            NO_CONNECTION,
            DynamoDbException.builder().message("synthetic").build(),
            ConditionalCheckFailedException.builder().message("synthetic").build()))
      assertThat(DynamoFailures.unavailable(failure))
          .as(failure.getClass().getSimpleName())
          .isTrue();
  }

  @Test
  void aConflictAnyOtherFailureAndAFailureThatOnlyWrapsOneAreNot() {
    for (Throwable failure :
        List.of(
            new DynamoWrites.Conflict(List.of("ConditionalCheckFailed")),
            new IllegalStateException("synthetic"),
            new IllegalStateException("wrapped", NO_CONNECTION),
            new IOException("synthetic")))
      assertThat(DynamoFailures.unavailable(failure))
          .as(failure.getClass().getSimpleName())
          .isFalse();
    assertThat(DynamoFailures.unavailable(null)).isFalse();
  }
}
