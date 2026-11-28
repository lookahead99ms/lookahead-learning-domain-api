package com.lookahead.domain.db.store.review;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.client.PlatformReviewS3Client;
import com.lookahead.domain.log.LogCapture;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/**
 * DLV-1016: the S3 object store of one runtime root, on the real S3 client class over a
 * hand-written {@link S3Client} that records every call. The tests pin how many S3 calls each
 * operation makes and in which order, above all for a listing that turns out malformed: it stops
 * after the page that showed the problem and asks for no further page.
 */
class PlatformReviewS3ObjectStoreTest {
  static final String BUCKET = "synthetic-review-bucket",
      ROOT = "platform-reviews/",
      OWNER = "123456789012";
  static final String KMS_KEY =
      "arn:aws:kms:us-east-2:123456789012:key/00000000-0000-0000-0000-000000000000";

  /**
   * Every S3 call in order, as one line each; each operation answers through a swappable function
   * (not a Mockito mock, see PlatformReviewS3ClientTest).
   */
  static final class RecordingS3 implements S3Client {
    final List<String> calls = new ArrayList<>();
    Function<GetObjectRequest, ResponseInputStream<GetObjectResponse>> get =
        request -> {
          throw new UnsupportedOperationException("getObject not stubbed");
        };
    BiFunction<PutObjectRequest, RequestBody, PutObjectResponse> put =
        (request, body) -> PutObjectResponse.builder().build();
    Function<ListObjectsV2Request, ListObjectsV2Response> list =
        request -> {
          throw new UnsupportedOperationException("listObjectsV2 not stubbed");
        };
    Function<DeleteObjectRequest, DeleteObjectResponse> delete =
        request -> DeleteObjectResponse.builder().build();

    @Override
    public String serviceName() {
      return "s3";
    }

    @Override
    public void close() {}

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
      calls.add(
          "GetObject "
              + request.bucket()
              + "/"
              + request.key()
              + " owner="
              + request.expectedBucketOwner());
      return get.apply(request);
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
      calls.add(
          "PutObject "
              + request.bucket()
              + "/"
              + request.key()
              + " owner="
              + request.expectedBucketOwner()
              + " ifMatch="
              + request.ifMatch()
              + " ifNoneMatch="
              + request.ifNoneMatch()
              + " encryption="
              + request.serverSideEncryptionAsString()
              + " key="
              + request.ssekmsKeyId());
      return put.apply(request, body);
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
      calls.add(
          "ListObjectsV2 "
              + request.bucket()
              + "/"
              + request.prefix()
              + " owner="
              + request.expectedBucketOwner()
              + " delimiter="
              + request.delimiter()
              + " token="
              + request.continuationToken());
      return list.apply(request);
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
      calls.add(
          "DeleteObject "
              + request.bucket()
              + "/"
              + request.key()
              + " owner="
              + request.expectedBucketOwner());
      return delete.apply(request);
    }
  }

  final RecordingS3 s3 = new RecordingS3();

  /**
   * The store under test: the S3 client of the bucket, and on it the store of the root {@code
   * platform-reviews/}.
   */
  PlatformReviewObjects store() {
    return store(ROOT);
  }

  PlatformReviewObjects store(String root) {
    return new PlatformReviewS3ObjectStore(
        new PlatformReviewS3Client(s3, BUCKET, OWNER, KMS_KEY), root);
  }

  static String listCall(String folder, String token) {
    return "ListObjectsV2 "
        + BUCKET
        + "/"
        + ROOT
        + folder
        + " owner="
        + OWNER
        + " delimiter=null token="
        + token;
  }

  static ListObjectsV2Response page(String next, String... objectKeys) {
    var objects = new ArrayList<S3Object>();
    for (String key : objectKeys) objects.add(S3Object.builder().key(key).build());
    return ListObjectsV2Response.builder()
        .isTruncated(next != null)
        .nextContinuationToken(next)
        .contents(objects)
        .build();
  }

  @Test
  void aListingAsksForOnePageAtATimeAndAnswersKeysRelativeToTheRoot() {
    s3.list =
        request ->
            request.continuationToken() == null
                ? page(
                    "page-2",
                    ROOT + "records/2026/11/06/REV-000001.json",
                    ROOT + "records/2026/11/06/REV-000002.json")
                : request.continuationToken().equals("page-2")
                    ? page("page-3")
                    : page(null, ROOT + "records/2026/11/07/REV-000003.json");
    try (var log = LogCapture.start()) {
      // The folder is the same with or without its trailing slash.
      for (String folder : List.of("records/", "records")) {
        s3.calls.clear();
        assertThat(store().listAll(folder))
            .containsExactly(
                "records/2026/11/06/REV-000001.json",
                "records/2026/11/06/REV-000002.json",
                "records/2026/11/07/REV-000003.json");
        assertThat(s3.calls)
            .containsExactly(
                listCall("records/", null),
                listCall("records/", "page-2"),
                listCall("records/", "page-3"));
      }
      assertThat(log.lines("upstream.call")).as("a listing that succeeds writes no line").isEmpty();
    }
    s3.calls.clear();
    s3.list = request -> page(null);
    assertThat(store().listAll("records/")).isEmpty();
    assertThat(s3.calls).containsExactly(listCall("records/", null));
  }

  @Test
  void aNameOutsideTheFolderStopsTheListingAfterThePageThatHeldIt() {
    try (var log = LogCapture.start()) {
      // On the second of three pages: two calls, the third page is never asked for.
      s3.list =
          request ->
              request.continuationToken() == null
                  ? page("page-2", ROOT + "records/a.json")
                  : request.continuationToken().equals("page-2")
                      ? page(
                          "page-3",
                          ROOT + "records/b.json",
                          "elsewhere/records/x.json",
                          ROOT + "records/c.json")
                      : page(null, ROOT + "records/d.json");
      assertUnavailable(() -> store().listAll("records/"));
      assertThat(s3.calls)
          .containsExactly(listCall("records/", null), listCall("records/", "page-2"));
      // On the first page: one call. A sibling folder with the same beginning is outside too.
      s3.calls.clear();
      s3.list = request -> page("page-2", ROOT + "records-other/x.json");
      assertUnavailable(() -> store().listAll("records/"));
      assertThat(s3.calls).containsExactly(listCall("records/", null));
      assertThat(log.lines("upstream.call"))
          .as("S3 answered; the malformed answer is not an S3 failure and writes no upstream line")
          .isEmpty();
    }
  }

  @Test
  void aTruncatedPageWithoutANewTokenStopsTheListingAfterThatPage() {
    try (var log = LogCapture.start()) {
      // Truncated, but no token for the next page: one call.
      s3.list =
          request ->
              ListObjectsV2Response.builder()
                  .isTruncated(true)
                  .nextContinuationToken(null)
                  .contents(S3Object.builder().key(ROOT + "records/x.json").build())
                  .build();
      assertUnavailable(() -> store().listAll("records"));
      assertThat(s3.calls).containsExactly(listCall("records/", null));
      // The second page answers the token it was asked with: two calls, never a third.
      s3.calls.clear();
      s3.list =
          request ->
              page(
                  "page-2",
                  ROOT + "records/" + (request.continuationToken() == null ? "a" : "b") + ".json");
      assertUnavailable(() -> store().listAll("records/"));
      assertThat(s3.calls)
          .containsExactly(listCall("records/", null), listCall("records/", "page-2"));
      assertThat(log.lines("upstream.call")).isEmpty();
    }
  }

  @Test
  void anS3FailureOnALaterPageIsLoggedOnceWithTheTimeSinceTheListingStarted() {
    s3.list =
        request -> {
          if (request.continuationToken() != null)
            throw SdkClientException.builder().message("synthetic private detail").build();
          try {
            Thread.sleep(80);
          } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
          }
          return page("page-2", ROOT + "records/a.json");
        };
    try (var log = LogCapture.start()) {
      assertUnavailable(() -> store().listAll("records/"));
      assertThat(s3.calls)
          .containsExactly(listCall("records/", null), listCall("records/", "page-2"));
      var line = log.only("upstream.call");
      assertThat(line.getLevel()).isEqualTo(Level.WARN);
      assertThat(line.getLoggerName()).isEqualTo("lookahead.upstream");
      assertThat(line.getFormattedMessage())
          .startsWith("S3 ListObjectsV2 on " + BUCKET + "/" + ROOT + "records/ failed in ")
          .endsWith(" ms: SdkClientException")
          .doesNotContain("synthetic private detail");
      var keys = LogCapture.keys(line);
      assertThat(keys)
          .containsEntry("event.outcome", "failure")
          .containsEntry("upstream.service", "s3")
          .containsEntry("upstream.operation", "ListObjectsV2")
          .containsEntry("upstream.resource", BUCKET + "/" + ROOT + "records/")
          .containsEntry("error.type", "SdkClientException");
      // The duration counts from the start of the listing (the first page took 80 ms), not from the
      // page that failed.
      assertThat((Long) keys.get("durationMs")).isGreaterThanOrEqualTo(80L);
    }
  }

  @Test
  void eachSingleObjectOperationIsOneS3CallOnTheKeyUnderTheRoot() {
    var store = store();
    s3.get =
        request ->
            new ResponseInputStream<>(
                GetObjectResponse.builder().eTag("opaque-1").build(),
                new ByteArrayInputStream("day".getBytes(StandardCharsets.UTF_8)));
    var read = store.read("reviews/2026-10-12.json").orElseThrow();
    assertThat(read.bytes()).isEqualTo("day".getBytes(StandardCharsets.UTF_8));
    assertThat(read.version()).isEqualTo("opaque-1");
    s3.get =
        request -> {
          throw S3Exception.builder().statusCode(404).build();
        };
    try (var log = LogCapture.start()) {
      assertThat(store.read("reviews/absent.json")).isEmpty();
      assertThat(log.lines("upstream.call"))
          .as("an absent object is an answer, not a failure")
          .isEmpty();
    }
    assertThat(store.write("reviews/new.json", new byte[] {1}, null)).isTrue();
    assertThat(store.write("reviews/day.json", new byte[] {2}, "opaque-1")).isTrue();
    for (int lost : new int[] {412, 409}) {
      s3.put =
          (request, body) -> {
            throw S3Exception.builder().statusCode(lost).build();
          };
      try (var log = LogCapture.start()) {
        assertThat(store.write("reviews/day.json", new byte[] {3}, "opaque-1"))
            .as("another writer won (" + lost + ")")
            .isFalse();
        assertThat(log.lines("upstream.call")).isEmpty();
      }
    }
    s3.put = (request, body) -> PutObjectResponse.builder().build();
    store.put("images/a.png", new byte[] {4});
    store.delete("images/a.png");
    String object = BUCKET + "/" + ROOT,
        put = " owner=" + OWNER,
        sealed = " encryption=aws:kms key=" + KMS_KEY;
    assertThat(s3.calls)
        .containsExactly(
            "GetObject " + object + "reviews/2026-10-12.json owner=" + OWNER,
            "GetObject " + object + "reviews/absent.json owner=" + OWNER,
            "PutObject "
                + object
                + "reviews/new.json"
                + put
                + " ifMatch=null ifNoneMatch=*"
                + sealed,
            "PutObject "
                + object
                + "reviews/day.json"
                + put
                + " ifMatch=opaque-1 ifNoneMatch=null"
                + sealed,
            "PutObject "
                + object
                + "reviews/day.json"
                + put
                + " ifMatch=opaque-1 ifNoneMatch=null"
                + sealed,
            "PutObject "
                + object
                + "reviews/day.json"
                + put
                + " ifMatch=opaque-1 ifNoneMatch=null"
                + sealed,
            "PutObject "
                + object
                + "images/a.png"
                + put
                + " ifMatch=null ifNoneMatch=null"
                + sealed,
            "DeleteObject " + object + "images/a.png owner=" + OWNER);
  }

  @Test
  void anS3FailureOfASingleObjectOperationIsOneCallAndOneLineNamingTheObject() {
    var store = store();
    var failure = SdkClientException.builder().message("synthetic private detail").build();
    s3.get =
        request -> {
          throw S3Exception.builder().statusCode(403).message("private bucket details").build();
        };
    s3.put =
        (request, body) -> {
          throw S3Exception.builder().statusCode(500).message("private bucket details").build();
        };
    s3.delete =
        request -> {
          throw failure;
        };
    var operations = new LinkedHashMap<String, ThrowingCallable>();
    operations.put(
        "GetObject on " + BUCKET + "/" + ROOT + "reviews/day.json was refused in ",
        () -> store.read("reviews/day.json"));
    operations.put(
        "PutObject on " + BUCKET + "/" + ROOT + "reviews/day.json failed in ",
        () -> store.write("reviews/day.json", new byte[0], "opaque-1"));
    operations.put(
        "PutObject on " + BUCKET + "/" + ROOT + "images/a.png failed in ",
        () -> store.put("images/a.png", new byte[0]));
    operations.put(
        "DeleteObject on " + BUCKET + "/" + ROOT + "images/b.png failed in ",
        () -> store.delete("images/b.png"));
    for (var operation : operations.entrySet()) {
      s3.calls.clear();
      try (var log = LogCapture.start()) {
        assertUnavailable(operation.getValue());
        assertThat(s3.calls).hasSize(1);
        var line = log.only("upstream.call");
        assertThat(line.getFormattedMessage()).startsWith("S3 " + operation.getKey());
        assertThat(line.getLevel())
            .isEqualTo(operation.getKey().contains("refused") ? Level.ERROR : Level.WARN);
      }
    }
    // An object that is too large to be a review file, or has no version, is a failed read: one
    // call, one line.
    s3.calls.clear();
    s3.get =
        request ->
            new ResponseInputStream<>(
                GetObjectResponse.builder().eTag(" ").build(),
                new ByteArrayInputStream(new byte[0]));
    try (var log = LogCapture.start()) {
      assertUnavailable(() -> store.read("reviews/day.json"));
      assertThat(s3.calls).hasSize(1);
      assertThat(log.only("upstream.call").getFormattedMessage())
          .startsWith("S3 GetObject on " + BUCKET + "/" + ROOT + "reviews/day.json failed in ")
          .endsWith(" ms: IOException");
    }
  }

  @Test
  void aKeyOutsideTheContractIsRefusedBeforeAnyS3Call() {
    var store = store();
    for (String key :
        new String[] {
          null,
          "",
          "../private",
          "records/../x.json",
          "/records/x.json",
          "records//x.json",
          "records/x y.json"
        }) {
      for (ThrowingCallable call :
          List.<ThrowingCallable>of(
              () -> store.read(key),
              () -> store.write(key, new byte[0], null),
              () -> store.put(key, new byte[0]),
              () -> store.delete(key)))
        assertThatThrownBy(call)
            .as("key " + key)
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid review object key");
    }
    for (String folder : new String[] {"", "/", "../private/", "records//", "records/../"})
      assertThatThrownBy(() -> store.listAll(folder))
          .as("folder " + folder)
          .isExactlyInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid review object key");
    assertThat(s3.calls).isEmpty();
    for (String key :
        List.of(
            "records/2026/11/06/REV-000001.json",
            "attachments/SUP-000001/01-screen.png",
            ".imported.json",
            "inbox/plan.json",
            "archive/plan-v7.json")) assertThat(key).matches(PlatformReviewObjects.KEY);
  }

  @Test
  void theRootIsALowerCasePrefixThatEndsWithASlash() {
    for (String root : List.of("Reviews/", "reviews", "/reviews/", "reviews//", ""))
      assertThatThrownBy(() -> store(root))
          .as("root " + root)
          .isExactlyInstanceOf(IllegalArgumentException.class)
          .hasMessage("Explicit review bucket, private prefix, owner and KMS key ARN are required");
    s3.list = request -> page(null, "release/live/records/x.json");
    assertThat(store("release/live/").listAll("records/")).containsExactly("records/x.json");
    assertThat(s3.calls)
        .containsExactly(
            "ListObjectsV2 "
                + BUCKET
                + "/release/live/records/ owner="
                + OWNER
                + " delimiter=null token=null");
  }

  private static void assertUnavailable(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UncheckedIOException.class)
        .hasMessage("java.io.IOException: Review object storage unavailable")
        .hasMessageNotContaining("private");
  }
}
