package com.citypulse.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(
        @Valid @NotNull RateLimit rateLimit
) {

    public record RateLimit(
            @NotNull Backend backend,
            @Min(1) int replenishRate,
            @Min(1) int burstCapacity
    ) {
    }

    public enum Backend {
        MEMORY,
        REDIS
    }
}
