package com.lookahead.domain.client;

import com.lookahead.domain.log.EventLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/**
 * The S3 calls behind the runtime object stores, on the private assets bucket: every method is one
 * S3 call with its error handling and its failure log line, addressed by the full object key. ETags
 * are opaque compare-and-swap versions, never content hashes. What a key may look like, which
 * prefix a store owns and how a listing is walked page by page are the store's business ({@code
 * db.store.review.PlatformReviewS3ObjectStore}).
 */
public final class PlatformReviewS3Client {
  private static final int MAX_READ_BYTES = 64 * 1024 * 1024;

  /** What GetObject answered: the object's bytes and its ETag. */
  public record Content(byte[] bytes, String eTag) {}

  /**
   * One ListObjectsV2 page as S3 answered it: the object keys in S3's order, whether S3 says more
   * follow, and the token for the next page. Nothing is checked here; {@code truncated} is S3's own
   * value.
   */
  public record Page(List<String> objectKeys, Boolean truncated, String nextToken) {}

  private final S3Client s3;
  private final String bucket, owner, kmsKey;

  public PlatformReviewS3Client(S3Client s3, String bucket, String owner, String kmsKey) {
    if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        || !owner.matches("[0-9]{12}")
        || !kmsKey.matches("arn:aws:kms:[a-z0-9-]+:" + owner + ":key/[a-fA-F0-9-]{36}"))
      // The text still says "private prefix" although this class takes none: it is the start-up
      // refusal operators know, kept word for word.
      throw new IllegalArgumentException(
          "Explicit review bucket, private prefix, owner and KMS key ARN are required");
    this.s3 = s3;
    this.bucket = bucket;
    this.owner = owner;
    this.kmsKey = kmsKey;
  }

  public Optional<Content> read(String objectKey) {
    long started = EventLog.started();
    try (var response =
        s3.getObject(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .expectedBucketOwner(owner)
                .build())) {
      byte[] bytes = response.readNBytes(MAX_READ_BYTES + 1);
      String version = response.response().eTag();
      if (bytes.length > MAX_READ_BYTES || version == null || version.isBlank())
        throw new IOException("Invalid review object");
      return Optional.of(new Content(bytes, version));
    } catch (S3Exception failure) {
      if (failure.statusCode() == 404) return Optional.empty();
      throw unavailable("GetObject", objectKey, started, failure);
    } catch (IOException | SdkException failure) {
      throw unavailable("GetObject", objectKey, started, failure);
    }
  }

  /**
   * Conditional PutObject: If-Match the expected ETag, or If-None-Match {@code *} when none is
   * expected. False when another writer won (412 or 409).
   */
  public boolean write(String objectKey, byte[] bytes, String expectedETag) {
    var request =
        putRequest(objectKey)
            .ifMatch(expectedETag)
            .ifNoneMatch(expectedETag == null ? "*" : null)
            .build();
    long started = EventLog.started();
    try {
      s3.putObject(request, RequestBody.fromBytes(bytes));
      return true;
    } catch (S3Exception failure) {
      if (failure.statusCode() == 412 || failure.statusCode() == 409) return false;
      throw unavailable("PutObject", request.key(), started, failure);
    } catch (SdkException failure) {
      throw unavailable("PutObject", request.key(), started, failure);
    }
  }

  public void put(String objectKey, byte[] bytes) {
    long started = EventLog.started();
    try {
      s3.putObject(putRequest(objectKey).build(), RequestBody.fromBytes(bytes));
    } catch (SdkException failure) {
      throw unavailable("PutObject", objectKey, started, failure);
    }
  }

  private PutObjectRequest.Builder putRequest(String objectKey) {
    return PutObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .expectedBucketOwner(owner)
        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
        .ssekmsKeyId(kmsKey);
  }

  /**
   * One page of the objects under {@code directory}, without a delimiter (DLV-971: records sit in
   * day folders); {@code token} is null for the first page. {@code started} is when the caller
   * began the whole listing ({@link EventLog#started()}), so a failure on a later page is logged
   * with the time since the listing began.
   */
  public Page listPage(String directory, String token, long started) {
    try {
      var page =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(directory)
                  .expectedBucketOwner(owner)
                  .continuationToken(token)
                  .build());
      return new Page(
          page.contents().stream().map(S3Object::key).toList(),
          page.isTruncated(),
          page.nextContinuationToken());
    } catch (SdkException failure) {
      throw unavailable("ListObjectsV2", directory, started, failure);
    }
  }

  public void delete(String objectKey) {
    long started = EventLog.started();
    try {
      s3.deleteObject(
          DeleteObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .expectedBucketOwner(owner)
              .build());
    } catch (SdkException failure) {
      throw unavailable("DeleteObject", objectKey, started, failure);
    }
  }

  /**
   * Failures only get a {@code lookahead.upstream} line: one review page reads up to 30 day files,
   * so a line per successful call would bury the rest. The line has the exception type, S3's status
   * and error code and (plain logs, owner 2026-11-17) the bucket and key, so a permission refusal
   * names the object it was refused on.
   */
  private UncheckedIOException unavailable(
      String operation, String objectKey, long started, Exception failure) {
    EventLog.upstreamFailed("S3", operation, bucket + "/" + objectKey, started, failure);
    return new UncheckedIOException(new IOException("Review object storage unavailable"));
  }
}
