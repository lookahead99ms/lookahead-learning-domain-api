package com.lookahead.domain.db.store.dynamodb;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

/**
 * DLV-1016: says whether a failure means that DynamoDB did not answer, so that a class outside the
 * store packages does not have to name an AWS SDK exception type to ask.
 */
public final class DynamoFailures {
  private DynamoFailures() {}

  /**
   * True for the store's own {@link DynamoWrites.Unavailable} (a failed write, and a failed read,
   * which is a subclass of it) and for the SDK's failures that reach the caller unwrapped: no
   * connection or another client-side failure ({@code SdkClientException}), and throttling or a
   * service error ({@code DynamoDbException}). Only the failure itself is looked at, never its
   * causes.
   */
  public static boolean unavailable(Throwable failure) {
    return failure instanceof DynamoWrites.Unavailable
        || failure instanceof SdkClientException
        || failure instanceof DynamoDbException;
  }
}
