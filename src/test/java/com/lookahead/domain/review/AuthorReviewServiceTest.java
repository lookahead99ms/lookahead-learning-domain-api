package com.lookahead.domain.review;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-970: the service keeps validation and policy; the store (either one) gets a validated submission and the whole history page. */
class AuthorReviewServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final AuthorPreviewAccessService access = mock(AuthorPreviewAccessService.class);
    final AccountPrincipal actor = new AccountPrincipal(UUID.randomUUID(), "synthetic@example.test", "Synthetic", true);
    final Artifact artifact = new Artifact("study-plan-review", "dlv-704/review-2026-09-19.1", "a".repeat(64), "DLV-704");
    final FakeStore store = new FakeStore();
    AuthorReviewService service;

    /** Records what the service asked for and answers from lists. */
    static final class FakeStore implements AuthorReviewStore {
        final List<Event> events = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        String hash; Submission submitted; UUID key; boolean cursorKnown = true;
        Event event(UUID supersedes) {
            return new Event(UUID.randomUUID(), "study-plan-review", "v", "h", "DLV-704", Decision.APPROVE, "", UUID.randomUUID(), Instant.EPOCH, UUID.randomUUID(), supersedes);
        }
        @Override public List<Event> latest(UUID actorId, String artifactId, int limit) { calls.add("latest " + artifactId + " " + limit); return events.subList(0, Math.min(limit, events.size())); }
        @Override public Optional<List<Event>> before(UUID actorId, String artifactId, UUID cursor, int limit) {
            calls.add("before " + artifactId + " " + cursor + " " + limit);
            int anchor = events.stream().map(Event::eventId).toList().indexOf(cursor);
            if (!cursorKnown || anchor < 0) return Optional.empty();
            var rest = events.subList(anchor + 1, events.size());
            return Optional.of(rest.subList(0, Math.min(limit, rest.size())));
        }
        @Override public Receipt submit(UUID actorId, String artifactId, UUID key, String requestHash, Artifact artifact, Submission normalized) {
            calls.add("submit " + artifactId); this.hash = requestHash; this.submitted = normalized; this.key = key;
            return new Receipt(event(normalized.supersedesEventId()), false, "PENDING_MAIN_RECONCILIATION");
        }
    }

    @BeforeEach void setup() throws Exception {
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, mapper.writeValueAsString(List.of(artifact)));
        service = new AuthorReviewService(access, new ReviewArtifactCatalog(manifest.toString(), mapper), store, mapper);
    }
    Submission submission(Decision decision, String comment, UUID prior) { return new Submission(artifact.artifactVersion(), artifact.contentHash(), artifact.ticketId(), decision, comment, prior); }

    @Test void aValidSubmissionReachesTheStoreNormalizedWithAStableHash() {
        var key = UUID.randomUUID();
        var receipt = service.submit(actor, "study-plan-review", key, submission(Decision.DECLINE, "  Explain the retry  ", null));
        assertThat(receipt.replayed()).isFalse();
        assertThat(store.key).isEqualTo(key);
        assertThat(store.submitted.comment()).isEqualTo("Explain the retry");
        assertThat(store.hash).matches("[0-9a-f]{64}");
        String first = store.hash;
        service.submit(actor, "study-plan-review", UUID.randomUUID(), submission(Decision.DECLINE, "Explain the retry", null));
        assertThat(store.hash).as("the same normalized payload hashes the same").isEqualTo(first);
        service.submit(actor, "study-plan-review", UUID.randomUUID(), submission(Decision.DECLINE, "Explain the retry again", null));
        assertThat(store.hash).isNotEqualTo(first);
        assertThat(service.artifacts(actor)).containsExactly(artifact);
    }

    @Test void policyRefusesBeforeTheStoreIsAsked() {
        assertThatThrownBy(() -> service.submit(actor, "study-plan-review", null, submission(Decision.APPROVE, "", null))).hasMessageContaining("invalid");
        assertThatThrownBy(() -> service.submit(actor, "study-plan-review", UUID.randomUUID(), null)).hasMessageContaining("invalid");
        for (var decision : List.of(Decision.DECLINE, Decision.NEED_MORE))
            assertThatThrownBy(() -> service.submit(actor, "study-plan-review", UUID.randomUUID(), submission(decision, "  ", null))).hasMessageContaining("Explain");
        for (String bad : List.of("x".repeat(2001), "<script>", "bad\u0000", "\ud800"))
            assertThatThrownBy(() -> service.submit(actor, "study-plan-review", UUID.randomUUID(), submission(Decision.APPROVE, bad, null))).hasMessageContaining("invalid");
        for (var stale : List.of(new Submission("old", artifact.contentHash(), "DLV-704", Decision.APPROVE, "", null),
                new Submission(artifact.artifactVersion(), "b".repeat(64), "DLV-704", Decision.APPROVE, "", null),
                new Submission(artifact.artifactVersion(), artifact.contentHash(), "DLV-705", Decision.APPROVE, "", null)))
            assertThatThrownBy(() -> service.submit(actor, "study-plan-review", UUID.randomUUID(), stale)).extracting("code").isEqualTo("REVIEW_ARTIFACT_STALE");
        assertThatThrownBy(() -> service.submit(actor, "unknown", UUID.randomUUID(), submission(Decision.APPROVE, "", null))).isInstanceOf(AccountFailure.class);
        doThrow(new AccountFailure(403, "AUTHOR_PREVIEW_ACCESS_DENIED", "denied")).when(access).requireAccess(actor);
        assertThatThrownBy(() -> service.artifacts(actor)).hasMessage("denied");
        assertThatThrownBy(() -> service.history(actor, "study-plan-review", 50, null)).hasMessage("denied");
        assertThatThrownBy(() -> service.submit(actor, "study-plan-review", UUID.randomUUID(), submission(Decision.APPROVE, "", null))).hasMessage("denied");
        assertThat(store.calls).isEmpty();
    }

    @Test void historyAsksForOneMoreThanThePageAndTurnsTheExtraIntoACursor() {
        var newest = store.event(null); var older = store.event(null); var oldest = store.event(null);
        store.events.addAll(List.of(newest, older, oldest));
        var page = service.history(actor, "study-plan-review", 2, null);
        assertThat(page.entries()).containsExactly(newest, older);
        assertThat(page.nextCursor()).isEqualTo(older.eventId());
        assertThat(store.calls).containsExactly("latest study-plan-review 3");
        var rest = service.history(actor, "study-plan-review", 2, older.eventId());
        assertThat(rest.nextCursor()).as("two of three fit: no cursor").isNull();
        assertThat(store.calls.getLast()).isEqualTo("before study-plan-review " + older.eventId() + " 3");
        store.cursorKnown = false;
        assertThatThrownBy(() -> service.history(actor, "study-plan-review", 2, UUID.randomUUID())).extracting("code").isEqualTo("REVIEW_EVENT_NOT_FOUND");
        for (int limit : new int[] {0, 101}) assertThatThrownBy(() -> service.history(actor, "study-plan-review", limit, null)).hasMessageContaining("invalid");
    }
}
