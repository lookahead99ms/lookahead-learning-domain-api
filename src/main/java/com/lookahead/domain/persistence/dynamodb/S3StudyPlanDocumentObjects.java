package com.lookahead.domain.persistence.dynamodb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Immutable, version-pinned private objects. No deletion, bucket creation or default client. */
public final class S3StudyPlanDocumentObjects {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public record Settings(String environment, String bucket, String expectedOwner, String kmsKeyArn) {
        public Settings {
            if (!"dev".equals(environment) && !"prod".equals(environment)) throw new IllegalArgumentException("Explicit environment required");
            if (bucket == null || bucket.length() > 63 || !bucket.matches("lookahead-" + environment + "-user-study-plan-details-[a-z0-9]+(?:-[a-z0-9]+)*"))
                throw new IllegalArgumentException("Explicit environment-specific plan bucket required");
            if (expectedOwner == null || !expectedOwner.matches("[0-9]{12}") || kmsKeyArn == null
                    || !kmsKeyArn.matches("arn:aws:kms:[a-z0-9-]+:" + expectedOwner + ":key/[a-fA-F0-9-]{36}"))
                throw new IllegalArgumentException("Explicit bucket owner and KMS key required");
        }
    }
    public record Id(UUID ownerId, UUID planId, UUID documentId) {
        public Id { Objects.requireNonNull(ownerId); Objects.requireNonNull(planId); Objects.requireNonNull(documentId); }
        String key() { return "study-plans/v1/" + ownerId + "/" + planId + "/" + documentId + ".json"; }
    }
    public record Descriptor(Id id, int byteLength, String sha256) {
        public Descriptor {
            Objects.requireNonNull(id);
            if (byteLength < 1 || byteLength > MAX_BYTES || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid plan document descriptor");
        }
        public static Descriptor of(Id id, byte[] bytes) {
            Objects.requireNonNull(bytes);
            return new Descriptor(id,bytes.length,StudyPlanDocumentCodec.sha256(bytes));
        }
    }
    public record Reference(Descriptor descriptor, String versionId, String eTag) {
        public Reference {
            Objects.requireNonNull(descriptor);
            bounded(versionId, "versionId"); bounded(eTag, "eTag");
            if ("null".equals(versionId)) throw new IllegalStateException("Versioned plan bucket required");
        }
    }
    private record Read(Reference reference, byte[] bytes) {}
    private final S3Client client;
    private final Settings settings;
    public S3StudyPlanDocumentObjects(S3Client client, Settings settings) {
        this.client = Objects.requireNonNull(client); this.settings = Objects.requireNonNull(settings);
    }
    public Settings settings() { return settings; }

    /** Caller must persist its matching durable upload intent before invoking this method. */
    public Reference upload(Descriptor descriptor, byte[] document) {
        byte[] bytes = Objects.requireNonNull(document).clone();
        if (!Descriptor.of(descriptor.id(),bytes).equals(descriptor)) throw new IllegalArgumentException("Upload does not match intent");
        try {
            var response = client.putObject(PutObjectRequest.builder().bucket(settings.bucket()).key(descriptor.id().key())
                    .expectedBucketOwner(settings.expectedOwner()).ifNoneMatch("*")
                    .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(settings.kmsKeyArn())
                    .contentType("application/json").contentLength((long)bytes.length)
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(descriptor.sha256())))
                    .metadata(metadata(descriptor)).build(), RequestBody.fromBytes(bytes));
            var reference = new Reference(descriptor,response.versionId(),response.eTag());
            read(reference); // Verify exact bytes/version before a pointer can be published.
            return reference;
        } catch (SdkException failure) {
            // Lost PUT reply or identical concurrent upload: only verified persisted bytes recover it.
            try { return readObject(descriptor,null).reference(); }
            catch (RuntimeException unavailable) { failure.addSuppressed(unavailable); }
            throw failure;
        }
    }
    public byte[] read(Reference reference) {
        var read = readObject(reference.descriptor(),reference.versionId());
        if (!read.reference().equals(reference)) throw new IllegalStateException("Stored object reference changed");
        return read.bytes();
    }
    private Read readObject(Descriptor descriptor, String version) {
        try (var input = client.getObject(GetObjectRequest.builder().bucket(settings.bucket()).key(descriptor.id().key())
                .expectedBucketOwner(settings.expectedOwner()).versionId(version).build())) {
            var response = input.response();
            if (response.contentLength() == null || response.contentLength() != descriptor.byteLength()
                    || !metadata(descriptor).equals(response.metadata()) || !"application/json".equals(response.contentType())
                    || response.serverSideEncryption() != ServerSideEncryption.AWS_KMS || !settings.kmsKeyArn().equals(response.ssekmsKeyId()))
                throw new IllegalStateException("Stored plan metadata does not match intent");
            byte[] bytes = input.readNBytes(descriptor.byteLength()+1);
            if (!Descriptor.of(descriptor.id(),bytes).equals(descriptor)) throw new IllegalStateException("Stored plan bytes do not match intent");
            return new Read(new Reference(descriptor,response.versionId(),response.eTag()),bytes);
        } catch (IOException failure) { throw new UncheckedIOException("Plan object read failed",failure); }
    }
    private static Map<String,String> metadata(Descriptor descriptor) {
        return Map.of("schema-version","1","owner-id",descriptor.id().ownerId().toString(),"plan-id",descriptor.id().planId().toString(),
                "document-id",descriptor.id().documentId().toString(),"sha256",descriptor.sha256());
    }
    private static void bounded(String value, String field) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 1024 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalStateException("Invalid S3 " + field);
    }
}
