package com.lookahead.domain.service.review;

import com.lookahead.domain.db.store.review.AuthorReviewManifestFileStore;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.validator.AuthorReviewManifestValidator;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.model.AuthorReviewModels.*;

/** Read-only, operator-supplied version manifest; no browser-provided paths or runtime source edits. */
@Component
public class AuthorReviewArtifactService {
    private final Map<String,Artifact> artifacts;
    public AuthorReviewArtifactService(@Value("${app.author-reviews.manifest:}") String manifest, ObjectMapper mapper, AuthorReviewManifestFileStore files) {
        if(manifest.isBlank()) { artifacts=Map.of(); return; }
        try {
            if(!files.absolute(manifest)) throw new IllegalArgumentException();
            var bytes=files.bytes(manifest,64*1024);
            if(bytes.isEmpty()) throw new IllegalArgumentException();
            var root=mapper.readTree(bytes.get());
            artifacts=AuthorReviewManifestValidator.validated(root);
        } catch(Exception failure) {
            EventLog.startupRefused("app.author-reviews.manifest (LOOKAHEAD_AUTHOR_REVIEW_MANIFEST)", "REVIEW_MANIFEST_INVALID",
                    "could not be read, or is not an absolute path to a JSON list of at most 100 entries of artifactId, artifactVersion,"
                    + " contentHash and ticketId (64 KB at most)", failure);
            throw new IllegalStateException("Author review manifest is invalid or unavailable");
        }
    }
    public List<Artifact> list() { return List.copyOf(artifacts.values()); }
    public Artifact require(String id) {
        Artifact value=artifacts.get(id);
        if(value==null) throw new AccountFailure(404,"REVIEW_ARTIFACT_NOT_FOUND","Review artifact is unavailable");
        return value;
    }
}
