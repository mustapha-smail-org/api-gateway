package com.citypulse.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.citypulse.gateway.ratelimit.InMemoryRateLimiter;

/**
 * Selects the {@link RateLimiter} backend via an explicit property
 * ({@code app.gateway.rate-limit.backend}) rather than
 * {@code @ConditionalOnMissingBean}: condition evaluation order between two
 * user-defined beans of the same type is not something to rely on, and
 * {@link com.citypulse.gateway.ratelimit.RateLimitingGlobalFilter} needs exactly
 * one {@code RateLimiter} bean to autowire regardless of which branch is active.
 * <p>
 * The redis branch does not reimplement anything: {@code spring-boot-starter-data-redis-reactive}
 * plus Spring Cloud Gateway's own {@code GatewayRedisAutoConfiguration} already
 * register a fully-wired {@link RedisRateLimiter} bean (named {@code redisRateLimiter})
 * the moment {@code ReactiveRedisConnectionFactory} is on the classpath — this
 * class only re-exposes that existing bean under the name our filter expects.
 * <p>
 * {@code @Primary} is required regardless of backend: Spring Cloud Gateway's own
 * {@code GatewayAutoConfiguration} unconditionally builds a
 * {@code RequestRateLimiterGatewayFilterFactory} (whether or not any route
 * actually uses the {@code RequestRateLimiter} filter — routing is not used here
 * for rate limiting, see {@link com.citypulse.gateway.ratelimit.RateLimitingGlobalFilter}),
 * and that factory autowires a single {@code RateLimiter<?>} by type. With the
 * redis starter on the classpath, {@code redisRateLimiter} always exists
 * alongside our own bean, so without {@code @Primary} that autowiring is
 * ambiguous even when the redis branch here is inactive.
 */
@Configuration
public class RateLimiterConfiguration {

    @Bean("gatewayRateLimiter")
    @Primary
    @ConditionalOnProperty(prefix = "app.gateway.rate-limit", name = "backend", havingValue = "memory", matchIfMissing = true)
    RateLimiter<?> inMemoryGatewayRateLimiter(GatewayProperties properties, ConfigurationService configurationService) {
        return new InMemoryRateLimiter(
                properties.rateLimit().replenishRate(),
                properties.rateLimit().burstCapacity(),
                configurationService
        );
    }

    @Bean("gatewayRateLimiter")
    @Primary
    @ConditionalOnProperty(prefix = "app.gateway.rate-limit", name = "backend", havingValue = "redis")
    RateLimiter<?> redisGatewayRateLimiter(RedisRateLimiter redisRateLimiter) {
        return redisRateLimiter;
    }
}
