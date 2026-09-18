package com.lookahead.learning.content.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record MutationReceipt(String requestHash, JsonNode response, int status, boolean deleted, UUID planId) {}
