package com.lookahead.domain.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record StudyPlanVersion(
    UUID id, JsonNode snapshot, JsonNode provenance, JsonNode recovery, JsonNode membership) {}
