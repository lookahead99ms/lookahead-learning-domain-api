package com.lookahead.domain.db.store.content;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.ProtectedContentModels.Asset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The content files of the mounted release on disk, read-only: gives the bytes of an asset the release published and
 * checks them against the manifest (DLV-1016: this was the reading half of ProtectedContentManifestService, which keeps the
 * allowlist; it is here because holding stored bytes is a store's work). It is given the folder and the version; it does not know the allowlist. In cloud mode the verified bytes
 * are kept in a bounded cache.
 */
// Exception, decided 2026-11-25 (DLV-1016): db.store also holds the file-system stores; this one is the persistence
// layer of the content files of the loaded release. The full note is on PublicationFileStore.
public class ProtectedContentFileStore {
    private final Path root;
    private final String version;
    private ProtectedContentCache cache;
    /**
     * {@code root}: the content folder of the release on disk, as {@link PublicationFileStore#realFolder} resolved it
     * ({@code null} when no release is configured); {@code version}: the release version, for the log line.
     */
    public ProtectedContentFileStore(PublicationFileStore.Folder root, String version, PublicationLocation location) {
        this(root, version);
        if (location.cloud()) cache = new ProtectedContentCache(location.cacheBytes(), location.cacheEntries());
    }
    public ProtectedContentFileStore(PublicationFileStore.Folder root, String version) { this.root = root == null ? null : root.path; this.version = version; }
    /** The verified bytes of a published asset. Whether the asset is the one the release published is the caller's check ({@code ProtectedContentManifestService.requirePublished}). */
    public byte[] read(Asset asset) {
        if (cache != null) return cache.read(asset.path() + ":" + asset.sha256(), () -> readVerified(asset));
        return readVerified(asset);
    }
    private byte[] readVerified(Asset asset) {
        String reason="ASSET_UNREADABLE",words="its file could not be read from the content release on disk";
        try {
            Path file=root.resolve(asset.path().substring("/content/".length())).normalize();
            for(Path current=file;current!=null && current.startsWith(root);current=current.getParent())if(Files.isSymbolicLink(current)){reason="ASSET_SYMLINK";words="its path holds a symbolic link";throw new IllegalStateException("Symbolic asset path");}
            if(!file.toRealPath().startsWith(root) || Files.size(file)>32*1024*1024){reason="ASSET_OUTSIDE_RELEASE";words="it resolves outside the content folder or is larger than 32 MB";throw new IllegalStateException("Invalid asset boundary");}
            byte[] bytes=Files.readAllBytes(file);String actual=Sha256.hex(bytes);
            if(!actual.equals(asset.sha256())){reason="ASSET_CHANGED";words="its bytes differ from the SHA-256 the manifest published";throw new IllegalStateException("Changed publication bytes");}return bytes;
        }catch(Exception failure){
            unreadable(reason,asset,version,words,reason.equals("ASSET_UNREADABLE")?failure:null);
            throw new AccountFailure(503,"CONTENT_UNAVAILABLE","This content is temporarily unavailable. Retry shortly.");
        }
    }

    /**
     * DLV-969 (owner, 2026-11-04): a 503 for content used to say only CONTENT_UNAVAILABLE. One WARN per reason (and
     * failure types) when it starts and once a minute while it lasts names the asset path, never a disk path.
     */
    private static final LogThrottle UNREADABLE = new LogThrottle();
    private static void unreadable(String reason,Asset asset,String version,String words,Exception failure) {
        String types=failure==null?null:EventLog.types(failure);
        if(!UNREADABLE.due(reason+"|"+types))return;
        var line=EventLog.failed(EventLog.EVENT,"content.read",reason).addKeyValue("content.path",asset.path())
                .addKeyValue("release.version",version);
        if(types!=null)line=line.addKeyValue("error.type",types);
        line.log("Content {} of release {} answered 503: {}{}",asset.path(),version,words,types==null?"":" ("+types+")");
    }
}
