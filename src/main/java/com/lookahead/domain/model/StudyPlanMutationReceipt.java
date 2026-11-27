package com.lookahead.domain.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record StudyPlanMutationReceipt(String requestHash, JsonNode response, int status, boolean deleted, UUID planId) {}
