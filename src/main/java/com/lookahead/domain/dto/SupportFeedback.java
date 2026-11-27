package com.lookahead.domain.dto;

import java.util.List;

/** The body of a support message. DLV-1016: was nested in SupportFeedbackService. */
public record SupportFeedback(String type, String subject, String message, String replyTo, List<SupportFeedbackAttachment> images) {}
