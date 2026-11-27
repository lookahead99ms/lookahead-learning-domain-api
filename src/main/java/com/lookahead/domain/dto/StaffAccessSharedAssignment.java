package com.lookahead.domain.dto;

import java.util.Set;
import java.util.UUID;

/**
 * What a co-author sees of a saved assignment (DLV-969): another person's role label, never their account id or
 * invitation email. Their own row stays theirs: their account id and their own name.
 */
public record StaffAccessSharedAssignment(UUID id,UUID accountId,String pendingEmail,String label,boolean pending,
    Set<String> roles,Set<String> topicGrants,boolean fullCurriculum,long revision) {}
