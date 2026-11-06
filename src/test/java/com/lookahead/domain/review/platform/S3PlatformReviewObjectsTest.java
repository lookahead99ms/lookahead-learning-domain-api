package com.lookahead.domain.review.platform;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.assertj.core.api.Assertions.*;
import java.util.function.*;

class S3PlatformReviewObjectsTest {
    /**
     * A hand-written S3Client, not a Mockito mock: other test classes switch Mockito's mock maker inside the same JVM, and
     * re-stubbing a mock after that failed with Mockito's own assertion (CI, 2026-11-06). Each operation is a swappable
     * function; the SDK's other methods keep their default UnsupportedOperationException.
     */
    static final class FakeS3 implements S3Client {
        Function<GetObjectRequest,ResponseInputStream<GetObjectResponse>> get=request->{throw new UnsupportedOperationException("getObject not stubbed");};
        BiFunction<PutObjectRequest,RequestBody,PutObjectResponse> put=(request,body)->{throw new UnsupportedOperationException("putObject not stubbed");};
        Function<ListObjectsV2Request,ListObjectsV2Response> list=request->{throw new UnsupportedOperationException("listObjectsV2 not stubbed");};
        Function<DeleteObjectRequest,DeleteObjectResponse> delete=request->DeleteObjectResponse.builder().build();
        @Override public String serviceName(){return "s3";}
        @Override public void close(){}
        @Override public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request){return get.apply(request);}
        @Override public PutObjectResponse putObject(PutObjectRequest request,RequestBody body){return put.apply(request,body);}
        @Override public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request){return list.apply(request);}
        @Override public DeleteObjectResponse deleteObject(DeleteObjectRequest request){return delete.apply(request);}
    }
    final FakeS3 client=new FakeS3();
    final String keyArn="arn:aws:kms:us-east-2:123456789012:key/00000000-0000-0000-0000-000000000000";
    S3PlatformReviewObjects store(){return new S3PlatformReviewObjects(client,"synthetic-review-bucket","platform-reviews/","123456789012",keyArn);}
    @Test void twoInstancesRetryConditionalDayWritesWithoutLosingEitherChange() throws Exception {
        var objects=new HashMap<String,PlatformReviewObjects.Stored>();var version=new AtomicInteger();
        client.get=request->{
            synchronized(objects) {
                var value=objects.get(request.key());if(value==null)throw S3Exception.builder().statusCode(404).build();
                return new ResponseInputStream<>(GetObjectResponse.builder().eTag(value.version()).build(),new ByteArrayInputStream(value.bytes()));
            }
        };
        client.put=(request,body)->{
            assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
            assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
            assertThat(request.ssekmsKeyId()).isEqualTo(keyArn);
            synchronized(objects) {
                var old=objects.get(request.key());
                if(old==null?!"*".equals(request.ifNoneMatch()):!old.version().equals(request.ifMatch()))
                    throw S3Exception.builder().statusCode(412).build();
                byte[] sent;
                try { sent=body.contentStreamProvider().newStream().readAllBytes(); } catch(java.io.IOException failure) { throw new UncheckedIOException(failure); }
                objects.put(request.key(),new PlatformReviewObjects.Stored(sent,"opaque-"+version.incrementAndGet()));
                return PutObjectResponse.builder().build();
            }
        };
        var start=new CountDownLatch(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<?>>();
            for(String item:List.of("first", "second"))tasks.add(pool.submit(()->{
                var store=store();var previous=store.read("reviews/2026-10-12.json");start.countDown();
                try {start.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}
                for(int attempt=0;attempt<8;attempt++) {
                    String bytes=previous.map(value->new String(value.bytes(),java.nio.charset.StandardCharsets.UTF_8)).orElse("")+item+"\n";
                    if(store.write("reviews/2026-10-12.json",bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8),previous.map(PlatformReviewObjects.Stored::version).orElse(null)))return;
                    previous=store.read("reviews/2026-10-12.json");
                }
                throw new AssertionError("Retry did not converge");
            }));
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertThat(new String(store().read("reviews/2026-10-12.json").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8)).contains("first", "second");
    }
    @Test void paginatesOnlyWithinThePrivatePrefixAndRedactsProviderFailures() {
        client.list=request->{
            assertThat(request.prefix()).isEqualTo("platform-reviews/reviews/");
            return ListObjectsV2Response.builder().isTruncated(request.continuationToken()==null)
                .nextContinuationToken(request.continuationToken()==null?"next":null)
                .contents(S3Object.builder().key(request.prefix()+(request.continuationToken()==null?"2026-10-11.json":"2026-10-12.json")).build()).build();
        };
        assertThat(store().list("reviews/")).containsExactly("reviews/2026-10-11.json","reviews/2026-10-12.json");
        assertThatThrownBy(()->store().read("../private")).isInstanceOf(IllegalArgumentException.class);
        client.get=request->{throw S3Exception.builder().statusCode(403).message("private bucket details").build();};
        assertThatThrownBy(()->store().read("reviews/day.json")).isInstanceOf(UncheckedIOException.class).hasMessageNotContaining("private bucket details");
        client.put=(request,body)->{throw S3Exception.builder().statusCode(409).build();};
        assertThat(store().write("reviews/day.json",new byte[0],"opaque")).isFalse();
    }

    /** DLV-971: records sit in day folders, so listAll lists without a delimiter across pages; keys allow the REV-/SUP- ids and the dot marker. */
    @Test void listAllWalksEveryPageWithoutADelimiterAndKeysFollowTheRecordLayout() {
        var store=store();
        client.list=request->{
            assertThat(request.prefix()).isEqualTo("platform-reviews/records/");
            assertThat(request.delimiter()).isNull();
            if(request.continuationToken()==null)return ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("page-2")
                .contents(S3Object.builder().key("platform-reviews/records/2026/11/06/REV-000001.json").build()).build();
            return ListObjectsV2Response.builder().isTruncated(false)
                .contents(S3Object.builder().key("platform-reviews/records/2026/11/07/REV-000002.json").build()).build();
        };
        assertThat(store.listAll("records/")).containsExactly("records/2026/11/06/REV-000001.json","records/2026/11/07/REV-000002.json");
        client.list=request->ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken(null)
            .contents(S3Object.builder().key("platform-reviews/records/x.json").build()).build();
        assertThatThrownBy(()->store.listAll("records")).isInstanceOf(UncheckedIOException.class);
        client.list=request->ListObjectsV2Response.builder().isTruncated(false)
            .contents(S3Object.builder().key("elsewhere/records/x.json").build()).build();
        assertThatThrownBy(()->store.listAll("records/")).isInstanceOf(UncheckedIOException.class);
        client.list=request->{throw software.amazon.awssdk.core.exception.SdkClientException.builder().message("synthetic").build();};
        assertThatThrownBy(()->store.listAll("records/")).isInstanceOf(UncheckedIOException.class);
        for(String key:List.of("records/2026/11/06/REV-000001.json","attachments/SUP-000001/01-screen.png",".imported.json","inbox/plan.json","archive/plan-v7.json"))
            assertThat(key).matches(PlatformReviewObjects.KEY);
        for(String key:List.of("../plan.json","records/../x.json","records/..","/records/x.json","records//x.json","records/x y.json",""))
            assertThat(key).doesNotMatch(PlatformReviewObjects.KEY);
        assertThatThrownBy(()->store.read("../plan.json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new S3PlatformReviewObjects(client,"synthetic-review-bucket","Reviews/","123456789012",keyArn)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new S3PlatformReviewObjects(client,"synthetic-review-bucket","release/live/","123456789012",keyArn)).isNotNull();
    }
}
