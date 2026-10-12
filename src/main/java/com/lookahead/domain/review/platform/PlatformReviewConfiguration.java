package com.lookahead.domain.review.platform;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Local uses the named-volume store; cloud uses explicitly configured private S3 storage.
 * Unconfigured storage answers 503 instead of silently saving ephemeral data.
 */
@Configuration
@Profile("accounts")
public class PlatformReviewConfiguration {
    @Bean
    PlatformReviewObjects platformReviewObjects(@Value("${app.platform-reviews.directory:}") String directory,
            @Value("${app.platform-reviews.bucket:}") String bucket,
            @Value("${app.platform-reviews.prefix:}") String prefix,
            @Value("${app.platform-reviews.kms-key:}") String kmsKey,
            com.lookahead.domain.publication.PublicationSettings publication,
            org.springframework.beans.factory.ObjectProvider<software.amazon.awssdk.services.s3.S3Client> clients) {
        boolean configured=!bucket.isBlank()||!prefix.isBlank()||!kmsKey.isBlank();
        if(publication.cloud()&&!directory.isBlank())throw new IllegalStateException("Cloud review storage must use S3");
        if(configured) {
            if(!publication.cloud()||!directory.isBlank())throw new IllegalStateException("S3 reviews require explicit cloud configuration");
            return new S3PlatformReviewObjects(clients.getObject(),bucket,prefix,publication.accountId(),kmsKey);
        }
        if (!directory.isBlank()) return new FileSystemPlatformReviewObjects(Path.of(directory));
        return new PlatformReviewObjects() {
            private UncheckedIOException missing() { return new UncheckedIOException(new java.io.IOException("Platform review storage is not configured")); }
            public Optional<Stored> read(String key) { throw missing(); }
            public boolean write(String key, byte[] bytes, String expectedVersion) { throw missing(); }
            public void put(String key, byte[] bytes) { throw missing(); }
            public List<String> list(String prefix) { throw missing(); }
            public void delete(String key) { throw missing(); }
        };
    }
}
