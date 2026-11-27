package com.lookahead.domain.dto;

/** The body of an account disable, enable or removal: the word the owner typed. DLV-1016: was nested in AccountRemovalService. */
public record AccountRemovalConfirmation(String confirmation) {}
