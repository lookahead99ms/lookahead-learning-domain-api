package com.lookahead.learning.content.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record PlanRecord(UUID id, long revision, UUID version, String goal, JsonNode progress, String createdAt, String updatedAt, String name, long planNumber) {
    public PlanRecord(UUID id, long revision, UUID version, String goal, JsonNode progress, String createdAt, String updatedAt) {
        this(id, revision, version, goal, progress, createdAt, updatedAt, goal, 0);
    }
}
