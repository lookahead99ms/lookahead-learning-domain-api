package com.lookahead.domain.dto;

/** The checked body of a triage on a platform review item: the new status and the owner's note. */
public record PlatformReviewTriage(String status, String ownerNote) {}
