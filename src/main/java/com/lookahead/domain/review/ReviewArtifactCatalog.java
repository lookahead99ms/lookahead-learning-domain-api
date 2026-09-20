package com.lookahead.domain.review;

import com.lookahead.learning.content.exception.AccountFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.ReviewModels.*;

/** Read-only, operator-supplied version manifest; no browser-provided paths or runtime source edits. */
@Component
public class ReviewArtifactCatalog {
    private final Map<String,Artifact> artifacts;
    public ReviewArtifactCatalog(@Value("${app.author-reviews.manifest:}") String manifest, ObjectMapper mapper) {
        if(manifest.isBlank()) { artifacts=Map.of(); return; }
        try {
            Path path=Path.of(manifest);
            if(!path.isAbsolute() || Files.size(path)>64*1024) throw new IllegalArgumentException();
            var root=mapper.readTree(Files.readAllBytes(path));
            if(!root.isArray() || root.size()>100) throw new IllegalArgumentException();
            Map<String,Artifact> loaded=new TreeMap<>();
            for(var node:root) {
                if(node.size()!=4 || !node.has("artifactId") || !node.has("artifactVersion") || !node.has("contentHash") || !node.has("ticketId")) throw new IllegalArgumentException();
                for(String key:List.of("artifactId","artifactVersion","contentHash","ticketId")) if(!node.path(key).isTextual()) throw new IllegalArgumentException();
                var artifact=new Artifact(node.path("artifactId").asText(),node.path("artifactVersion").asText(),node.path("contentHash").asText(),node.path("ticketId").asText());
                if(!artifact.artifactId().matches("[a-z0-9][a-z0-9-]{0,79}") || !artifact.artifactVersion().matches("[a-zA-Z0-9][a-zA-Z0-9/._-]{0,119}")
                        || !artifact.contentHash().matches("[a-f0-9]{64}") || !artifact.ticketId().matches("DLV-[1-9][0-9]{0,5}")
                        || loaded.putIfAbsent(artifact.artifactId(),artifact)!=null) throw new IllegalArgumentException();
            }
            artifacts=Collections.unmodifiableMap(loaded);
        } catch(Exception failure) { throw new IllegalStateException("Author review manifest is invalid or unavailable"); }
    }
    public List<Artifact> list() { return List.copyOf(artifacts.values()); }
    public Artifact require(String id) {
        Artifact value=artifacts.get(id);
        if(value==null) throw new AccountFailure(404,"REVIEW_ARTIFACT_NOT_FOUND","Review artifact is unavailable");
        return value;
    }
}
