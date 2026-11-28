package com.lookahead.domain.dto;

/**
 * The body of a Local account maintenance request: the word the owner typed. DLV-1016: was nested
 * in AccountMaintenanceService.
 */
public record AccountMaintenanceConfirmation(String confirmation) {}
