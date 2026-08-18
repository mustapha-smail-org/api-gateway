package com.citypulse.gateway.exception;

/**
 * Mirrors the shape (not the values) of {@code catalog-service}'s
 * {@code ApiErrorCode}: an all-caps machine-readable code carried as a top-level
 * {@code code} extension on every problem+json body this service originates.
 */
public enum GatewayErrorCode {
    RATE_LIMIT_EXCEEDED,
    SERVICE_UNAVAILABLE,
    BAD_GATEWAY,
    INTERNAL_ERROR
}
