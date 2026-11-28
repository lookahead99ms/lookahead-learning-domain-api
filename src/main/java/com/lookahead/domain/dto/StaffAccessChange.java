package com.lookahead.domain.dto;

import java.util.Set;
import java.util.UUID;

/** The body of a staff assignment save. DLV-1016: was nested in StaffAccessService. */
public record StaffAccessChange(
    UUID id,
    UUID accountId,
    String pendingEmail,
    Set<String> roles,
    Set<String> topicGrants,
    boolean fullCurriculum,
    long revision) {}
