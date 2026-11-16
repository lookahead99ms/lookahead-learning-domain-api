package com.lookahead.domain.persistence.dynamodb;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.core.SdkBytes;

/** Binary chunking only; callers must validate JSON and authorize the owning plan separately. */
public final class StudyPlanDocumentCodec {
    public static final int CHUNK_BYTES = 240 * 1024;
    public static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CHUNKS = (MAX_DOCUMENT_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    public record Manifest(int schemaVersion, int byteLength, int chunkCount, String sha256) {
        public Manifest {
            if (schemaVersion != 1 || byteLength < 1 || byteLength > MAX_DOCUMENT_BYTES
                    || chunkCount != (byteLength + CHUNK_BYTES - 1) / CHUNK_BYTES
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid study plan document manifest");
            }
        }
    }

    public record EncodedDocument(Manifest manifest, List<SdkBytes> chunks) {
        public EncodedDocument {
            Objects.requireNonNull(manifest, "manifest");
            chunks = List.copyOf(chunks);
        }
    }

    public EncodedDocument encode(byte[] document) {
        Objects.requireNonNull(document, "document");
        if (document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Study plan document must be 1 byte through 8 MiB");
        }
        var chunks = new ArrayList<SdkBytes>();
        for (int offset = 0; offset < document.length; offset += CHUNK_BYTES) {
            chunks.add(SdkBytes.fromByteArray(java.util.Arrays.copyOfRange(document, offset,
                    Math.min(offset + CHUNK_BYTES, document.length))));
        }
        return new EncodedDocument(new Manifest(1, document.length, chunks.size(), sha256(document)), chunks);
    }

    public byte[] decode(Manifest manifest, List<SdkBytes> chunks) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.size() != manifest.chunkCount()) {
            throw new IllegalArgumentException("Incomplete study plan document");
        }
        var output = new ByteArrayOutputStream(manifest.byteLength());
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = Objects.requireNonNull(chunks.get(i), "chunk").asByteArray();
            int expected = Math.min(CHUNK_BYTES, manifest.byteLength() - i * CHUNK_BYTES);
            if (chunk.length != expected) {
                throw new IllegalArgumentException("Invalid study plan chunk length");
            }
            output.writeBytes(chunk);
        }
        byte[] document = output.toByteArray();
        if (!sha256(document).equals(manifest.sha256())) {
            throw new IllegalArgumentException("Study plan document checksum mismatch");
        }
        return document;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java SHA-256 provider unavailable", impossible);
        }
    }
}
