package com.lookahead.domain.dto;

/** One image sent with a support message: its file name and its data. DLV-1016: was nested in SupportFeedbackService. */
public record SupportFeedbackAttachment(String name, String data) {}
