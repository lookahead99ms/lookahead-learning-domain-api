package com.lookahead.domain.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record StudyPlanRecord(UUID id, long revision, UUID version, String goal, JsonNode progress, String createdAt, String updatedAt, String name, long planNumber) {
    public StudyPlanRecord(UUID id, long revision, UUID version, String goal, JsonNode progress, String createdAt, String updatedAt) {
        this(id, revision, version, goal, progress, createdAt, updatedAt, goal, 0);
    }
}
