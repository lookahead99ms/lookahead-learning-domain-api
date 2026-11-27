package com.lookahead.domain.client;

import com.lookahead.domain.model.PublicationSelection;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Owner-checked reads of the selected bucket; listing is bounded to the live prefix a deployment discovers. */
public final class PublicationS3Client implements PublicationObjectClient {
    private final S3Client client;
    private final PublicationSelection selection;
    public PublicationS3Client(S3Client client, PublicationSelection selection) { this.client = client; this.selection = selection; }
    @Override public InputStream open(String key) {
        return client.getObject(request -> request.bucket(selection.bucket()).key(key).expectedBucketOwner(selection.accountId()));
    }
    @Override public List<ListedObject> list(String prefix) {
        List<ListedObject> result = new ArrayList<>();
        Set<String> continuationTokens = new HashSet<>();
        String token = null;
        int pages = 0;
        do {
            if (++pages > PublicationObjectClient.MAX_LIVE_OBJECTS + 1)
                throw new IllegalStateException("Cloud publication listing is incomplete or exceeds its request bound");
            String continuation = token;
            ListObjectsV2Response page = client.listObjectsV2(request -> request.bucket(selection.bucket()).prefix(prefix)
                    .expectedBucketOwner(selection.accountId()).maxKeys(PublicationObjectClient.MAX_LIVE_OBJECTS + 1)
                    .continuationToken(continuation));
            for (S3Object object : page.contents()) result.add(new ListedObject(object.key(), object.lastModified()));
            if (result.size() > PublicationObjectClient.MAX_LIVE_OBJECTS) throw new IllegalStateException("Live prefix holds more objects than releases");
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            if (Boolean.TRUE.equals(page.isTruncated()) && (token == null || token.isBlank() || !continuationTokens.add(token)))
                throw new IllegalStateException("Cloud publication listing is incomplete or exceeds its request bound");
        } while (token != null);
        return result;
    }
}
