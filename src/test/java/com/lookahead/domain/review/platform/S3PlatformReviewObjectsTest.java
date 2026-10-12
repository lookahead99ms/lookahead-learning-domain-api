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
import static org.mockito.Mockito.*;

class S3PlatformReviewObjectsTest {
    final S3Client client=mock(S3Client.class);
    final String keyArn="arn:aws:kms:us-east-2:123456789012:key/00000000-0000-0000-0000-000000000000";
    S3PlatformReviewObjects store(){return new S3PlatformReviewObjects(client,"synthetic-review-bucket","platform-reviews/","123456789012",keyArn);}
    @Test void twoInstancesRetryConditionalDayWritesWithoutLosingEitherChange() throws Exception {
        var objects=new HashMap<String,PlatformReviewObjects.Stored>();var version=new AtomicInteger();
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(call->{
            GetObjectRequest request=call.getArgument(0);
            synchronized(objects) {
                var value=objects.get(request.key());if(value==null)throw S3Exception.builder().statusCode(404).build();
                return new ResponseInputStream<>(GetObjectResponse.builder().eTag(value.version()).build(),new ByteArrayInputStream(value.bytes()));
            }
        });
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call->{
            PutObjectRequest request=call.getArgument(0);RequestBody body=call.getArgument(1);
            assertThat(request.expectedBucketOwner()).isEqualTo("123456789012");
            assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
            assertThat(request.ssekmsKeyId()).isEqualTo(keyArn);
            synchronized(objects) {
                var old=objects.get(request.key());
                if(old==null?!"*".equals(request.ifNoneMatch()):!old.version().equals(request.ifMatch()))
                    throw S3Exception.builder().statusCode(412).build();
                objects.put(request.key(),new PlatformReviewObjects.Stored(body.contentStreamProvider().newStream().readAllBytes(),"opaque-"+version.incrementAndGet()));
                return PutObjectResponse.builder().build();
            }
        });
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
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->{
            ListObjectsV2Request request=call.getArgument(0);
            assertThat(request.prefix()).isEqualTo("platform-reviews/reviews/");
            return ListObjectsV2Response.builder().isTruncated(request.continuationToken()==null)
                .nextContinuationToken(request.continuationToken()==null?"next":null)
                .contents(S3Object.builder().key(request.prefix()+(request.continuationToken()==null?"2026-10-11.json":"2026-10-12.json")).build()).build();
        });
        assertThat(store().list("reviews/")).containsExactly("reviews/2026-10-11.json","reviews/2026-10-12.json");
        assertThatThrownBy(()->store().read("../private")).isInstanceOf(IllegalArgumentException.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).message("private bucket details").build());
        assertThatThrownBy(()->store().read("reviews/day.json")).isInstanceOf(UncheckedIOException.class).hasMessageNotContaining("private bucket details");
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenThrow(S3Exception.builder().statusCode(409).build());
        assertThat(store().write("reviews/day.json",new byte[0],"opaque")).isFalse();
    }
}
