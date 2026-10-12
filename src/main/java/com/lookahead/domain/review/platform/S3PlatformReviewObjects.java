package com.lookahead.domain.review.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Private S3 objects; ETags are opaque compare-and-swap versions, never content hashes. */
public final class S3PlatformReviewObjects implements PlatformReviewObjects {
    private static final int MAX_READ_BYTES = 64 * 1024 * 1024;
    private final S3Client s3;
    private final String bucket, prefix, owner, kmsKey;
    public S3PlatformReviewObjects(S3Client s3, String bucket, String prefix, String owner, String kmsKey) {
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || !prefix.matches("[a-z0-9][a-z0-9_-]*(?:/[a-z0-9][a-z0-9_-]*)*/")
                || !owner.matches("[0-9]{12}")
                || !kmsKey.matches("arn:aws:kms:[a-z0-9-]+:" + owner + ":key/[a-fA-F0-9-]{36}"))
            throw new IllegalArgumentException("Explicit review bucket, private prefix, owner and KMS key ARN are required");
        this.s3=s3;this.bucket=bucket;this.prefix=prefix;this.owner=owner;this.kmsKey=kmsKey;
    }
    private String key(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]*(?:/[a-z0-9][a-z0-9.-]*)*"))
            throw new IllegalArgumentException("Invalid review object key");
        return prefix + key;
    }
    @Override public Optional<Stored> read(String key) {
        String objectKey=key(key);
        try (var response=s3.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).expectedBucketOwner(owner).build())) {
            byte[] bytes=response.readNBytes(MAX_READ_BYTES+1);
            String version=response.response().eTag();
            if(bytes.length>MAX_READ_BYTES || version==null || version.isBlank())throw new IOException("Invalid review object");
            return Optional.of(new Stored(bytes,version));
        } catch(S3Exception failure) {
            if(failure.statusCode()==404)return Optional.empty();
            throw unavailable();
        } catch(IOException | software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public boolean write(String key, byte[] bytes, String expectedVersion) {
        var request=putRequest(key).ifMatch(expectedVersion).ifNoneMatch(expectedVersion==null?"*":null).build();
        try { s3.putObject(request,RequestBody.fromBytes(bytes));return true; }
        catch(S3Exception failure) { if(failure.statusCode()==412||failure.statusCode()==409)return false;throw unavailable(); }
        catch(software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public void put(String key,byte[] bytes) {
        try { s3.putObject(putRequest(key).build(),RequestBody.fromBytes(bytes)); }
        catch(software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    private PutObjectRequest.Builder putRequest(String key) {
        return PutObjectRequest.builder().bucket(bucket).key(key(key)).expectedBucketOwner(owner)
            .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKey);
    }
    @Override public List<String> list(String requestedPrefix) {
        String directory=key(requestedPrefix.endsWith("/")?requestedPrefix.substring(0,requestedPrefix.length()-1):requestedPrefix)+"/";
        List<String> keys=new ArrayList<>();String token=null;
        try {
            do {
                var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(directory).delimiter("/")
                    .expectedBucketOwner(owner).continuationToken(token).build());
                for(var object:page.contents()) {
                    String name=object.key();
                    if(!name.startsWith(directory)||name.substring(directory.length()).contains("/"))throw unavailable();
                    keys.add(name.substring(prefix.length()));
                }
                String next=page.isTruncated()?page.nextContinuationToken():null;
                if(page.isTruncated()&&(next==null||next.equals(token)))throw unavailable();
                token=next;
            } while(token!=null);
            return List.copyOf(keys);
        } catch(software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public void delete(String key) {
        try {s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(key)).expectedBucketOwner(owner).build());}
        catch(software.amazon.awssdk.core.exception.SdkException failure) {throw unavailable();}
    }
    private static UncheckedIOException unavailable() {return new UncheckedIOException(new IOException("Review object storage unavailable"));}
}
