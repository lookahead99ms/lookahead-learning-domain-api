package com.lookahead.domain.dto;

import java.util.UUID;

/** The body of a test session start: the test account to act as. */
public record TestSessionStart(UUID accountId) {}
