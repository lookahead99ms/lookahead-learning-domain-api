package com.lookahead.domain.client;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogCapture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.assertj.core.api.Assertions.*;
import java.util.function.*;

class PlatformReviewS3ClientTest {
    /**
     * A hand-written S3Client, not a Mockito mock: other test classes switch Mockito's mock maker inside the same JVM, and
     * re-stubbing a mock after that failed with Mockito's own assertion (CI, 2026-11-06). Each operation is a swappable
     * function; the SDK's other methods keep their default UnsupportedOperationException.
     */
    static final class FakeS3 implements S3Client {
        Function<GetObjectRequest,ResponseInputStream<GetObjectResponse>> get=request->{throw new UnsupportedOperationException("getObject not stubbed");};
        BiFunction<PutObjectRequest,RequestBody,PutObjectResponse> put=(request,body)->{throw new UnsupportedOperationException("putObject not stubbed");};
        Function<ListObjectsV2Request,ListObjectsV2Response> list=request->{throw new UnsupportedOperationException("listObjectsV2 not stubbed");};
        final AtomicInteger listCalls=new AtomicInteger();
        Function<DeleteObjectRequest,DeleteObjectResponse> delete=request->DeleteObjectResponse.builder().build();
        @Override public String serviceName(){return "s3";}
        @Override public void close(){}
        @Override public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request){return get.apply(request);}
        @Override public PutObjectResponse putObject(PutObjectRequest request,RequestBody body){return put.apply(request,body);}
        @Override public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request){listCalls.incrementAndGet();return list.apply(request);}
        @Override public DeleteObjectResponse deleteObject(DeleteObjectRequest request){return delete.apply(request);}
    }
    final FakeS3 client=new FakeS3();
    final String keyArn="arn:aws:kms:us-east-2:123456789012:key/00000000-0000-0000-0000-000000000000";
    /** DLV-1016: the client of the bucket; it takes full object keys (the store adds its prefix and checks the key). */
    PlatformReviewS3Client s3(){return new PlatformReviewS3Client(client,"synthetic-review-bucket","123456789012",keyArn);}
    @Test void twoInstancesRetryConditionalDayWritesWithoutLosingEitherChange() throws Exception {
        String day="platform-reviews/reviews/2026-10-12.json";
        var objects=new HashMap<String,PlatformReviewS3Client.Content>();var version=new AtomicInteger();
        client.get=request->{
            synchronized(objects) {
                var value=objects.get(request.key());if(value==null)throw S3Exception.builder().statusCode(404).build();
                return new ResponseInputStream<>(GetObjectResponse.builder().eTag(value.eTag()).build(),new ByteArrayInputStream(value.bytes()));
            }
        };
        client.put=(request,body)->{
            assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
            assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
            assertThat(request.ssekmsKeyId()).isEqualTo(keyArn);
            synchronized(objects) {
                var old=objects.get(request.key());
                if(old==null?!"*".equals(request.ifNoneMatch()):!old.eTag().equals(request.ifMatch()))
                    throw S3Exception.builder().statusCode(412).build();
                byte[] sent;
                try { sent=body.contentStreamProvider().newStream().readAllBytes(); } catch(IOException failure) { throw new UncheckedIOException(failure); }
                objects.put(request.key(),new PlatformReviewS3Client.Content(sent,"opaque-"+version.incrementAndGet()));
                return PutObjectResponse.builder().build();
            }
        };
        var start=new CountDownLatch(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<?>>();
            for(String item:List.of("first", "second"))tasks.add(pool.submit(()->{
                var s3=s3();var previous=s3.read(day);start.countDown();
                try {start.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}
                for(int attempt=0;attempt<8;attempt++) {
                    String bytes=previous.map(value->new String(value.bytes(),StandardCharsets.UTF_8)).orElse("")+item+"\n";
                    if(s3.write(day,bytes.getBytes(StandardCharsets.UTF_8),previous.map(PlatformReviewS3Client.Content::eTag).orElse(null)))return;
                    previous=s3.read(day);
                }
                throw new AssertionError("Retry did not converge");
            }));
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertThat(new String(s3().read(day).orElseThrow().bytes(),StandardCharsets.UTF_8)).contains("first", "second");
    }
    @Test void aPageIsAskedForUnderTheGivenFolderAndProviderFailuresAreRedacted() {
        client.list=request->{
            assertThat(request.bucket()).isEqualTo("synthetic-review-bucket");
            assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
            assertThat(request.prefix()).isEqualTo("platform-reviews/reviews/");
            return ListObjectsV2Response.builder().isTruncated(request.continuationToken()==null)
                .nextContinuationToken(request.continuationToken()==null?"next":null)
                .contents(S3Object.builder().key(request.prefix()+(request.continuationToken()==null?"2026-10-11.json":"2026-10-12.json")).build()).build();
        };
        var first=s3().listPage("platform-reviews/reviews/",null,EventLog.started());
        assertThat(first.objectKeys()).containsExactly("platform-reviews/reviews/2026-10-11.json");
        assertThat(first.truncated()).isTrue();assertThat(first.nextToken()).isEqualTo("next");
        var second=s3().listPage("platform-reviews/reviews/","next",EventLog.started());
        assertThat(second.objectKeys()).containsExactly("platform-reviews/reviews/2026-10-12.json");
        assertThat(second.truncated()).isFalse();assertThat(second.nextToken()).isNull();
        assertThat(client.listCalls.get()).as("one S3 call per page").isEqualTo(2);
        client.get=request->{throw S3Exception.builder().statusCode(403).message("private bucket details").build();};
        assertThatThrownBy(()->s3().read("platform-reviews/reviews/day.json")).isInstanceOf(UncheckedIOException.class).hasMessageNotContaining("private bucket details");
        client.put=(request,body)->{throw S3Exception.builder().statusCode(409).build();};
        assertThat(s3().write("platform-reviews/reviews/day.json",new byte[0],"opaque")).isFalse();
    }

    /**
     * DLV-971: records sit in day folders, so a page is listed without a delimiter. DLV-1016: one call per page, answered as
     * S3 said it; the store checks the page and decides whether to ask for the next.
     */
    @Test void aPageIsOneCallWithoutADelimiterAnsweredUncheckedAndAFailureIsLoggedFromTheGivenStart() {
        var s3=s3();
        client.list=request->{
            assertThat(request.prefix()).isEqualTo("platform-reviews/records/");
            assertThat(request.delimiter()).isNull();
            assertThat(request.continuationToken()).isEqualTo("page-2");
            return ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken(null)
                .contents(S3Object.builder().key("platform-reviews/records/2026/11/07/REV-000002.json").build(),S3Object.builder().key("elsewhere/records/x.json").build()).build();
        };
        // A name outside the folder and a truncated page without a token come back as they are: checking them is the store's part.
        var page=s3.listPage("platform-reviews/records/","page-2",EventLog.started());
        assertThat(page.objectKeys()).containsExactly("platform-reviews/records/2026/11/07/REV-000002.json","elsewhere/records/x.json");
        assertThat(page.truncated()).isTrue();assertThat(page.nextToken()).isNull();
        assertThat(client.listCalls.get()).isEqualTo(1);
        client.list=request->ListObjectsV2Response.builder().isTruncated(false).build();
        assertThat(s3.listPage("platform-reviews/records/",null,EventLog.started()).objectKeys()).isEmpty();
        client.list=request->{throw SdkClientException.builder().message("synthetic").build();};
        try(var log=LogCapture.start()) {
            // The caller began its listing 5 seconds ago: the line counts from there, not from this call.
            long started=EventLog.started()-5_000_000_000L;
            assertThatThrownBy(()->s3.listPage("platform-reviews/records/","page-3",started)).isInstanceOf(UncheckedIOException.class)
                .hasMessage("java.io.IOException: Review object storage unavailable");
            var line=log.only("upstream.call");
            assertThat(line.getFormattedMessage()).startsWith("S3 ListObjectsV2 on synthetic-review-bucket/platform-reviews/records/ failed in ").endsWith(" ms: SdkClientException");
            assertThat((Long)LogCapture.keys(line).get("durationMs")).isBetween(5_000L,65_000L);
        }
        assertThat(client.listCalls.get()).isEqualTo(3);
        for(String[] wrong:new String[][]{{"Synthetic_Bucket","123456789012",keyArn},{"synthetic-review-bucket","12345",keyArn},
                {"synthetic-review-bucket","123456789012","arn:aws:kms:us-east-2:999999999999:key/00000000-0000-0000-0000-000000000000"},{"synthetic-review-bucket","123456789012",""}})
            assertThatThrownBy(()->new PlatformReviewS3Client(client,wrong[0],wrong[1],wrong[2])).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Explicit review bucket, private prefix, owner and KMS key ARN are required");
        assertThat(new PlatformReviewS3Client(client,"synthetic-review-bucket","123456789012",keyArn)).isNotNull();
    }
}
