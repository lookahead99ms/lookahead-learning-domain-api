package com.lookahead.domain.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewModels {
    private ReviewModels() {}
    public enum Decision { APPROVE, DECLINE, NEED_MORE }
    public record Artifact(String artifactId, String artifactVersion, String contentHash, String ticketId) {}
    public record Submission(String artifactVersion, String contentHash, String ticketId,
                             Decision decision, String comment, UUID supersedesEventId) {}
    public record Event(UUID eventId, String artifactId, String artifactVersion, String contentHash,
                        String ticketId, Decision decision, String comment, UUID actorId,
                        Instant recordedAt, UUID idempotencyKey, UUID supersedesEventId) {}
    public record Receipt(Event event, boolean replayed, String reconciliationStatus) {}
    public record History(List<Event> entries, UUID nextCursor) {}
}
