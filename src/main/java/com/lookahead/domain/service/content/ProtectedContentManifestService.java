package com.lookahead.domain.service.content;

import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.model.ProtectedContentModels.*;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.util.CatalogIds;
import com.lookahead.domain.validator.ProtectedContentManifestValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Immutable publication allowlist. Source manifests and bytes are server-owned; ProtectedContentFileStore (db.store.content) reads the bytes. */
@Component
@Profile("accounts")
public class ProtectedContentManifestService {
    private final Map<String,Asset> assets;
    private final PublicationFileStore.Folder root;
    private final String version;
    private final Set<String> freeContentIds;
    private static final LogThrottle NOT_PUBLISHED = new LogThrottle();
    @Autowired
    public ProtectedContentManifestService(ObjectMapper mapper, PublicationLocation location, PublicationFileStore files) {
        this(mapper, location.manifest(), location.contentRoot(), files);
    }
    public ProtectedContentManifestService(ObjectMapper mapper,String manifestPath,
            String contentRoot, PublicationFileStore files) {
        if(manifestPath.isBlank() && contentRoot.isBlank()){assets=Map.of();root=null;version="disabled";freeContentIds=Set.of();return;}
        try {
            ProtectedContentManifestValidator.requireBoth(manifestPath,contentRoot);
            root=files.realFolder(contentRoot);
            var text=files.text(manifestPath,32*1024*1024);
            if(text.isEmpty())throw new IllegalArgumentException("Publication manifest too large");
            JsonNode data=mapper.readTree(text.get());
            Manifest manifest=ProtectedContentManifestValidator.validated(data);
            version=manifest.version();assets=manifest.assets();freeContentIds=manifest.freeContentIds();
        } catch(Exception error){throw new IllegalStateException("Cannot load protected content publication",error);}
    }
    public String version(){return version;}
    public Set<String> freeContentIds(){return freeContentIds;}
    public Set<String> contentGrants(Set<String> grants) {
        Set<String> courseGrants=CatalogIds.normalize(grants);
        var result=new HashSet<>(freeContentIds);
        for(var asset:assets.values()) if(asset.matchesScopes(courseGrants)) result.addAll(asset.contentIds());
        return Set.copyOf(result);
    }
    public Optional<Asset> find(String path){return Optional.ofNullable(assets.get(path));}
    /**
     * Refuses an asset that is not the one this release published, with the same line and answer as before (DLV-1016:
     * this check was the first statement of the file store's read; the allowlist is this class's, so the check is here
     * and the three services that read content bytes call it just before they read).
     */
    public void requirePublished(Asset asset) {
        if (!asset.equals(assets.get(asset.path()))) {
            // The policy writes its own line (DLV-1016: it called a static method of the file store for it): the same
            // words and keys, at most once a minute.
            if (NOT_PUBLISHED.due("ASSET_NOT_PUBLISHED"))
                EventLog.failed(EventLog.EVENT,"content.read","ASSET_NOT_PUBLISHED").addKeyValue("content.path",asset.path()).addKeyValue("release.version",version)
                        .log("Content {} of release {} answered 503: {}{}",asset.path(),version,"it is not the asset this release published","");
            throw new AccountFailure(503,"CONTENT_UNAVAILABLE","Content is unavailable.");
        }
    }
    /**
     * The content folder of the release on disk as the file store resolved it ({@code null} when no release is
     * configured). The policy only holds it and hands it to ProtectedContentFileStore; it reads no path.
     */
    public PublicationFileStore.Folder root(){return root;}
}
