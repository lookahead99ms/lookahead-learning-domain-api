package com.lookahead.domain.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.lookahead.domain.review.ReviewModels.*;

/**
 * DLV-970: the author review events of one actor, as whole aggregate operations. {@link DynamoAuthorReviewStore} keeps
 * the contract on the four DynamoDB tables (the PostgreSQL store retired 2026-11-17). {@link AuthorReviewService} keeps validation and policy; the store keeps the transaction and the
 * conflicts only a transaction can decide (replay, supersession, unknown actor).
 */
public interface AuthorReviewStore {
    /** The actor's newest events on the artifact first, at most {@code limit}. */
    List<Event> latest(UUID actorId, String artifactId, int limit);

    /**
     * The actor's events on the artifact older than {@code cursor}, newest first, at most {@code limit}; empty when the
     * cursor is not one of this actor's events on this artifact.
     */
    Optional<List<Event>> before(UUID actorId, String artifactId, UUID cursor, int limit);

    /**
     * One transaction: the actor exists ({@code 401 AUTHENTICATION_REQUIRED} otherwise), the same key replays the same
     * request ({@code 409 REVIEW_IDEMPOTENCY_CONFLICT} for a different one), the submission supersedes exactly the
     * actor's latest event on the artifact ({@code 409 REVIEW_SUPERSESSION_CONFLICT} otherwise), and the event is
     * appended with the next sequence number.
     */
    Receipt submit(UUID actorId, String artifactId, UUID key, String requestHash, Artifact artifact, Submission normalized);
}
