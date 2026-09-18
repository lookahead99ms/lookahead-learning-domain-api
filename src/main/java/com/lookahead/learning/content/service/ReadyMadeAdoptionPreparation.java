package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.validator.ReadyMadeTemplateMapper;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Read-only preparation for a future atomic adoption command. Never a write authorization:
 * a transaction must repeat this check and enforce account reservations before saving.
 * No controller or advertised capability uses this service until that contract is complete.
 */
@Service
@Profile("accounts")
public final class ReadyMadeAdoptionPreparation {
    public record Coverage(List<String> requiredCourseIds, List<String> coveredCourseIds,
                           List<String> missingCourseIds, List<String> missingContentIds) {
        public Coverage {
            requiredCourseIds = List.copyOf(requiredCourseIds);
            coveredCourseIds = List.copyOf(coveredCourseIds);
            missingCourseIds = List.copyOf(missingCourseIds);
            missingContentIds = List.copyOf(missingContentIds);
        }
        public boolean complete() { return missingCourseIds.isEmpty() && missingContentIds.isEmpty(); }
    }

    /** A prepared draft is owner-bound and is never an entitlement or persisted reservation. */
    public record Prepared(UUID accountId, String templatePath, String templateSha256,
                           String publicationVersion, Coverage coverage,
                           ReadyMadeTemplateMapper.Mapping mapping) {}

    private final ProtectedContentPolicy publication;
    private final AccountRepository accounts;
    private final SnapshotValidator catalog;
    private final ObjectMapper mapper;
    private final ReadyMadeTemplateMapper templates;

    public ReadyMadeAdoptionPreparation(ProtectedContentPolicy publication, AccountRepository accounts,
                                       SnapshotValidator catalog, ObjectMapper mapper) {
        this.publication = publication;
        this.accounts = accounts;
        this.catalog = catalog;
        this.mapper = mapper;
        this.templates = new ReadyMadeTemplateMapper(mapper);
    }

    public Prepared prepare(UUID accountId, String templateId, String expectedSha256) {
        Objects.requireNonNull(accountId, "An owner is required");
        if (templateId == null || templateId.length() > 180 || !templateId.matches("[a-z0-9-]+-d[0-9]+-h[0-9]+")
                || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Supply a bounded template identity and raw SHA-256 pin");
        if (!accounts.isEnabled(accountId))
            throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to prepare this plan");
        String path = "/content/study-plans/templates/" + templateId + ".json";
        var asset = publication.find(path).orElseThrow(() ->
                new AccountFailure(404, "CONTENT_NOT_FOUND", "Template not found"));
        if (!expectedSha256.equals(asset.sha256()))
            throw new AccountFailure(409, "TEMPLATE_CHANGED", "The published template changed. Review its current version.");
        if (!asset.tier().equals("pro") || !asset.scopeMatch().equals("all")
                || !asset.mediaType().equals("application/json") || !asset.contentIds().isEmpty())
            throw unavailable();

        ReadyMadeTemplateMapper.Mapping mapping;
        JsonNode template;
        try {
            // The production loader validates actual bytes, path boundaries and symlinks on every preparation.
            template = mapper.readTree(publication.read(asset));
            if (!templateId.equals(template.path("templateId").asText())) throw unavailable();
            catalog.validateTemplateReferences(template);
            mapping = templates.map(template, asset.sha256());
            if (!mapping.topicIds().equals(asset.scopes())) throw unavailable();
        } catch (AccountFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Server-owned catalog defects must not expose source data or appear as a learner payload failure.
            throw unavailable();
        }

        // Read current complete-course grants after source verification; no role, claimed access or future term check.
        Set<String> grants = accounts.findTopicGrants(accountId);
        var covered = new TreeSet<>(asset.scopes());
        covered.retainAll(grants);
        var missing = new TreeSet<>(asset.scopes());
        missing.removeAll(grants);
        var missingContent = new TreeSet<String>();
        for (var reference : template.path("references"))
            if (missing.contains(reference.path("topicId").asText()))
                missingContent.add(reference.path("contentId").asText());
        Coverage coverage = new Coverage(List.copyOf(new TreeSet<>(asset.scopes())), List.copyOf(covered),
                List.copyOf(missing), List.copyOf(missingContent));
        // Exact IDs are bounded by source validation (100 topics / 10,000 references); never silently truncate.
        return new Prepared(accountId, path, asset.sha256(), publication.version(), coverage,
                coverage.complete() ? mapping : null);
    }

    private static AccountFailure unavailable() {
        return new AccountFailure(503, "TEMPLATE_UNAVAILABLE", "This template is temporarily unavailable.");
    }
}
