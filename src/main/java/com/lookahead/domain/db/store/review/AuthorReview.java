package com.lookahead.domain.db.store.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.lookahead.domain.model.AuthorReviewModels.*;

/**
 * DLV-970: the author review events of one actor, as whole aggregate operations. {@link AuthorReviewStore} keeps
 * the contract on the four DynamoDB tables (the PostgreSQL store retired 2026-11-17). {@code AuthorReviewService} keeps validation and policy; the store keeps the transaction and the
 * conflicts only a transaction can decide (replay, supersession, unknown actor).
 */
public interface AuthorReview {
    /** The actor's newest events on the artifact first, at most {@code limit}. */
    List<Event> latest(UUID actorId, String artifactId, int limit);

    /**
     * The actor's events on the artifact older than {@code cursor}, newest first, at most {@code limit}; empty when the
     * cursor is not one of this actor's events on this artifact.
     */
    Optional<List<Event>> before(UUID actorId, String artifactId, UUID cursor, int limit);

    /**
     * The append was not made, for a reason only the retried, conditional append can find: the actor has no account record
     * ({@link Reason#ACTOR_UNKNOWN}), the key already recorded a different request ({@link Reason#IDEMPOTENCY_CONFLICT}), or the
     * submission does not supersede the actor's latest event on the artifact ({@link Reason#SUPERSESSION_CONFLICT}). DLV-1016:
     * the store reports the reason; {@code AuthorReviewService} answers it (401, 409, 409).
     */
    final class NotRecorded extends RuntimeException {
        public enum Reason { ACTOR_UNKNOWN, IDEMPOTENCY_CONFLICT, SUPERSESSION_CONFLICT }
        private final Reason reason;
        public NotRecorded(Reason reason) { super("Review event not recorded: " + reason); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    /**
     * One transaction: the actor exists, the same key replays the same request, the submission supersedes exactly the
     * actor's latest event on the artifact, and the event is appended with the next sequence number; {@link NotRecorded}
     * with its reason otherwise.
     */
    Receipt submit(UUID actorId, String artifactId, UUID key, String requestHash, Artifact artifact, Submission normalized);
}
