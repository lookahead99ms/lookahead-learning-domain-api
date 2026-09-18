package com.lookahead.learning.content.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record PlanVersion(UUID id, JsonNode snapshot, JsonNode provenance, JsonNode recovery, JsonNode membership) {}
