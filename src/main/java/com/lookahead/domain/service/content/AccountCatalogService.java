package com.lookahead.domain.service.content;

import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.model.AccountCatalog;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.validator.AccountCatalogValidator;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lookahead.domain.util.JsonChecks.*;

/**
 * The trusted account catalog of the mounted release: every published content item with its topics, routes and
 * aliases, and the algorithm and ranking versions the release supports. Loaded and checked once at start; read-only.
 * DLV-1016: this was the catalog half of StudyPlanSnapshotValidator. The checks are in {@link AccountCatalogValidator},
 * the checked catalog is the value {@link AccountCatalog}; this service loads it and answers the services, the
 * validators read the value.
 */
@Service
@Profile("accounts")
public final class AccountCatalogService {
    private final ObjectMapper mapper;
    private final AccountCatalog catalog;

    @Autowired
    public AccountCatalogService(ObjectMapper mapper, PublicationLocation publication, PublicationFileStore files) {
        this(mapper, publication.accountCatalog(), files);
    }

    public AccountCatalogService(ObjectMapper mapper, String catalogPath, PublicationFileStore files) {
        this(mapper, readCatalog(mapper, catalogPath, files));
    }

    public AccountCatalogService(ObjectMapper mapper, JsonNode source) {
        this.mapper = mapper;
        this.catalog = AccountCatalogValidator.validated(source);
    }

    private static JsonNode readCatalog(ObjectMapper mapper, String path, PublicationFileStore files) {
        try {
            var text = files.text(path, 32 * 1024 * 1024);
            require(text.isPresent(), "Trusted catalog too large");
            return mapper.readTree(text.get());
        } catch (Exception ex) { throw new IllegalStateException("Cannot load trusted account catalog", ex); }
    }

    /** The checked catalog as a value: what the study plan validators read (DLV-1016: they depended on this service). */
    public AccountCatalog catalog() { return catalog; }
    public Set<String> allTopicIds() { return catalog.allTopicIds(); }
    public String catalogVersion() { return catalog.catalogVersion(); }
    public JsonNode metadata() {
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "account-catalog/v1");
        result.put("catalogVersion", catalogVersion());
        result.set("algorithmVersions", catalog.document().path("algorithmVersions").deepCopy());
        result.putArray("variationPolicies").add("topic-tie-v1");
        result.putArray("studyActivityPolicies").add("completion-day-v1");
        result.set("rankingVersions", catalog.document().path("rankingVersions").deepCopy());
        result.set("topicIds", mapper.valueToTree(new TreeSet<>(catalog.allTopicIds())));
        return result;
    }

    public boolean isAccessible(String canonicalId, Set<String> grants) { return catalog.isAccessible(canonicalId, grants); }

    /** Canonicalizes a known metadata alias; unknown historical IDs remain opaque, not authorized. */
    public String canonicalContentId(String id) { return catalog.canonicalContentId(id); }
}
