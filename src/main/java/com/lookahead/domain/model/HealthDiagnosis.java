package com.lookahead.domain.model;

/**
 * Why a readiness check is DOWN: a stable code such as {@code DATA_VERSION_BEHIND} and a sentence
 * without secrets. DLV-1016: was nested in log.HealthLog, which made the model record
 * PublicationLiveRelease depend on the log package.
 */
public record HealthDiagnosis(String reason, String words) {}
