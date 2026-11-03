package com.lookahead.domain.persistence.dynamodb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3StudyPlanDocumentObjectsTest {
    private final S3StudyPlanDocumentObjects.Id id=new S3StudyPlanDocumentObjects.Id(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    private S3StudyPlanDocumentObjects.Descriptor descriptor(byte[] bytes){return S3StudyPlanDocumentObjects.Descriptor.of(id,bytes);}
    @Test void sdkWritesConditionallyWithOwnerEncryptionAndChecksumAndReadsPinnedMaximumDocument() throws Exception {
        try(var fixture=new S3StudyPlanDocumentHttpFixture()) {
            byte[] bytes=new byte[S3StudyPlanDocumentObjects.MAX_BYTES];new java.util.Random(970).nextBytes(bytes);
            var ref=fixture.objects().upload(descriptor(bytes),bytes);
            assertThat(fixture.objects().read(ref)).isEqualTo(bytes);
            var put=fixture.requests.getFirst();
            assertThat(put.path()).isEqualTo(fixture.path(id));
            var headers=new java.util.TreeMap<String,java.util.List<String>>(String.CASE_INSENSITIVE_ORDER);headers.putAll(put.headers());
            assertThat(headers.get("If-None-Match")).containsExactly("*");
            assertThat(headers.get("x-amz-expected-bucket-owner")).containsExactly("123456789012");
            assertThat(headers.get("x-amz-server-side-encryption")).containsExactly("aws:kms");
            assertThat(headers.get("x-amz-checksum-sha256")).containsExactly(java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(ref.descriptor().sha256())));
            assertThat(fixture.requests.getLast().query()).contains("versionId=fixture%2Fversion%2B1");
            var original=fixture.latest.get(fixture.path(id));
            fixture.latest.put(fixture.path(id),new S3StudyPlanDocumentHttpFixture.Blob(new byte[]{8},original.metadata(),"replacement",original.etag(),original.kms()));
            assertThat(fixture.objects().read(ref)).isEqualTo(bytes);
        }
    }
    @Test void lostPutReplyAndIdenticalRetriesRecoverWithoutOverwriting() throws Exception {
        try(var fixture=new S3StudyPlanDocumentHttpFixture()) {
            fixture.loseNextPutResponse=true;
            byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var ref=fixture.objects().upload(descriptor(bytes),bytes);
            assertThat(fixture.objects().upload(descriptor(bytes),bytes)).isEqualTo(ref);
            assertThat(fixture.versions).hasSize(1);
            assertThatThrownBy(()->fixture.objects().upload(descriptor(new byte[]{1,2}),new byte[]{1,2})).isInstanceOf(SdkException.class);
            assertThat(fixture.objects().read(ref)).isEqualTo(bytes);
        }
    }
    @Test void failedPutOrFailedVerificationDoesNotInventAReference() throws Exception {
        try(var fixture=new S3StudyPlanDocumentHttpFixture()) {
            fixture.failPut=true;
            assertThatThrownBy(()->fixture.objects().upload(descriptor(new byte[]{1}),new byte[]{1})).isInstanceOf(SdkException.class);
            assertThat(fixture.latest).isEmpty();
            fixture.failPut=false;fixture.failGet=true;
            assertThatThrownBy(()->fixture.objects().upload(descriptor(new byte[]{1}),new byte[]{1})).isInstanceOf(SdkException.class);
        }
    }
    @Test void corruptionWrongIdentitySizeOrEncryptionFailsClosed() throws Exception {
        try(var fixture=new S3StudyPlanDocumentHttpFixture()) {
            var ref=fixture.objects().upload(descriptor(new byte[]{1,2}),new byte[]{1,2});
            var original=fixture.latest.get(fixture.path(id));
            for(var bad:java.util.List.of(
                    new S3StudyPlanDocumentHttpFixture.Blob(new byte[]{3,4},original.metadata(),original.version(),original.etag(),original.kms()),
                    new S3StudyPlanDocumentHttpFixture.Blob(new byte[]{1},original.metadata(),original.version(),original.etag(),original.kms()),
                    new S3StudyPlanDocumentHttpFixture.Blob(original.bytes(),Map.of("owner-id",UUID.randomUUID().toString()),original.version(),original.etag(),original.kms()),
                    new S3StudyPlanDocumentHttpFixture.Blob(original.bytes(),original.metadata(),original.version(),"other",original.kms()),
                    new S3StudyPlanDocumentHttpFixture.Blob(original.bytes(),original.metadata(),original.version(),original.etag(),"other-key"))) {
                fixture.corrupt(ref,bad);
                assertThatIllegalStateException().isThrownBy(()->fixture.objects().read(ref));
            }
        }
    }
    @Test void inputsAndUnversionedReferencesAreRejectedBeforeIo() {
        var client=mock(S3Client.class,withSettings().mockMaker(MockMakers.PROXY));
        var objects=new S3StudyPlanDocumentObjects(client,S3StudyPlanDocumentHttpFixture.SETTINGS);
        for(byte[] bytes:new byte[][]{new byte[0],new byte[S3StudyPlanDocumentObjects.MAX_BYTES+1]})
            assertThatIllegalArgumentException().isThrownBy(()->descriptor(bytes));
        assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentObjects.Descriptor(id,1,"x"));
        assertThatIllegalArgumentException().isThrownBy(()->objects.upload(descriptor(new byte[]{1}),new byte[]{2}));
        for(String version:new String[]{null,""," ","null","x".repeat(1025),"bad\nversion"})
            assertThatIllegalStateException().isThrownBy(()->new S3StudyPlanDocumentObjects.Reference(descriptor(new byte[]{1}),version,"opaque"));
        assertThatIllegalStateException().isThrownBy(()->new S3StudyPlanDocumentObjects.Reference(descriptor(new byte[]{1}),"version",null));
        verifyNoInteractions(client);
    }
    @Test void bucketSettingsRequireEnvironmentAccountAndKeyBinding() {
        var good=S3StudyPlanDocumentHttpFixture.SETTINGS;
        for(String environment:new String[]{null,"local","dev"})
            assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentObjects.Settings(environment,good.bucket(),good.expectedOwner(),good.kmsKeyArn()));
        for(String bucket:new String[]{null,"shared-curriculum",good.bucket()+"x".repeat(64)})
            assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentObjects.Settings("prod",bucket,good.expectedOwner(),good.kmsKeyArn()));
        for(String owner:new String[]{null,"x","000000000000"})
            assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentObjects.Settings("prod",good.bucket(),owner,good.kmsKeyArn()));
        for(String kms:new String[]{null,"alias/plan-key"})
            assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentObjects.Settings("prod",good.bucket(),good.expectedOwner(),kms));
    }
    @Test void malformedResponseAndTruncatedStreamNeverReturnBytes() {
        var client=mock(S3Client.class,withSettings().mockMaker(MockMakers.PROXY));
        var d=descriptor(new byte[]{1,2});var ref=new S3StudyPlanDocumentObjects.Reference(d,"v1","etag");
        var objects=new S3StudyPlanDocumentObjects(client,S3StudyPlanDocumentHttpFixture.SETTINGS);
        var valid=GetObjectResponse.builder().contentLength(2L).contentType("application/json").versionId("v1").eTag("etag")
                .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(S3StudyPlanDocumentHttpFixture.SETTINGS.kmsKeyArn())
                .metadata(Map.of("schema-version","1","owner-id",id.ownerId().toString(),"plan-id",id.planId().toString(),"document-id",id.documentId().toString(),"sha256",d.sha256())).build();
        for(var response:java.util.List.of(valid.toBuilder().contentLength((Long)null).build(),valid.toBuilder().contentType("text/plain").build(),
                valid.toBuilder().serverSideEncryption(ServerSideEncryption.AES256).build())) {
            when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(response,AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1,2}))));
            assertThatIllegalStateException().isThrownBy(()->objects.read(ref));
        }
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(valid,AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1}))));
        assertThatIllegalStateException().isThrownBy(()->objects.read(ref));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(valid,AbortableInputStream.create(new InputStream(){public int read()throws IOException{throw new IOException("fixture");}})));
        assertThatThrownBy(()->objects.read(ref)).isInstanceOf(java.io.UncheckedIOException.class);
    }
}
