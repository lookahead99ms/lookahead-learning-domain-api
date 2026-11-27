package com.lookahead.domain.db.store.review;

import com.lookahead.domain.client.PlatformReviewS3Client;
import com.lookahead.domain.log.EventLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The object store of one runtime root in the private assets bucket (reviews/, support-requests/, delivery/): the keys
 * of {@link PlatformReviewObjects} under this store's prefix. It checks every key, walks a listing page by page and
 * checks each page before asking for the next; every S3 call itself is {@link PlatformReviewS3Client}'s.
 */
public final class PlatformReviewS3ObjectStore implements PlatformReviewObjects {
    private final PlatformReviewS3Client client;
    private final String prefix;
    public PlatformReviewS3ObjectStore(PlatformReviewS3Client client, String prefix) {
        if (!prefix.matches("[a-z0-9][a-z0-9_-]*(?:/[a-z0-9][a-z0-9_-]*)*/"))
            throw new IllegalArgumentException("Explicit review bucket, private prefix, owner and KMS key ARN are required");
        this.client=client;this.prefix=prefix;
    }
    /** The S3 object key of a store key; a key outside the contract is refused before any S3 call. */
    private String key(String key) {
        if (key == null || !key.matches(PlatformReviewObjects.KEY))
            throw new IllegalArgumentException("Invalid review object key");
        return prefix + key;
    }
    @Override public Optional<Stored> read(String key) {
        return client.read(key(key)).map(content->new Stored(content.bytes(),content.eTag()));
    }
    @Override public boolean write(String key, byte[] bytes, String expectedVersion) { return client.write(key(key),bytes,expectedVersion); }
    @Override public void put(String key,byte[] bytes) { client.put(key(key),bytes); }
    /**
     * DLV-971: records sit in day folders (records/YYYY/MM/DD/), so the store lists without a delimiter. Each page is
     * checked before the next is asked for: a name outside the folder, or a truncated page without a new token, ends
     * the listing there, so a malformed listing costs no further S3 call.
     */
    @Override public List<String> listAll(String requestedPrefix) {
        String directory=key(requestedPrefix.endsWith("/")?requestedPrefix.substring(0,requestedPrefix.length()-1):requestedPrefix)+"/";
        List<String> keys=new ArrayList<>();String token=null;
        long started=EventLog.started();
        do {
            var page=client.listPage(directory,token,started);
            for(String name:page.objectKeys()) {
                if(!name.startsWith(directory))throw unavailable();
                keys.add(name.substring(prefix.length()));
            }
            String next=page.truncated()?page.nextToken():null;
            if(page.truncated()&&(next==null||next.equals(token)))throw unavailable();
            token=next;
        } while(token!=null);
        return List.copyOf(keys);
    }
    @Override public void delete(String key) { client.delete(key(key)); }
    /** The same failure the client answers when S3 fails; a malformed listing writes no log line (S3 answered). */
    private static UncheckedIOException unavailable() {return new UncheckedIOException(new IOException("Review object storage unavailable"));}
}
