package com.citypulse.gateway.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.cloud.gateway.support.ConfigurationService;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;

/**
 * A single-instance token-bucket {@link RateLimiter}, used when
 * {@code app.gateway.rate-limit.backend=memory} (the default). Correct for exactly
 * one gateway replica — Render's free tier gives us one anyway — but limits become
 * per-replica, not global, the moment there is more than one. Switch to
 * {@code backend=redis} (see {@link RateLimiterConfiguration}) once that matters.
 * <p>
 * Mirrors {@code RedisRateLimiter}'s key shape ({@code routeId + "/" + id}) and
 * {@code Config} field names so the two backends are drop-in equivalents from a
 * route-filter-args perspective.
 */
public class InMemoryRateLimiter extends AbstractRateLimiter<InMemoryRateLimiter.Config> {

    public static final String CONFIGURATION_PROPERTY_NAME = "in-memory-rate-limiter";

    /** Buckets idle longer than this are eligible for eviction. */
    private static final long IDLE_EVICTION_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long EVICTION_PERIOD_SECONDS = TimeUnit.MINUTES.toSeconds(5);

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Config defaultConfig;
    private final ScheduledExecutorService evictionExecutor;

    public InMemoryRateLimiter(int defaultReplenishRate, int defaultBurstCapacity, ConfigurationService configurationService) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.defaultConfig = new Config().setReplenishRate(defaultReplenishRate).setBurstCapacity(defaultBurstCapacity);

        this.evictionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "in-memory-rate-limiter-eviction");
            thread.setDaemon(true);
            return thread;
        });
        this.evictionExecutor.scheduleAtFixedRate(
                this::evictIdleBuckets, EVICTION_PERIOD_SECONDS, EVICTION_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config routeConfig = getConfig().getOrDefault(routeId, defaultConfig);
        String key = routeId + "/" + id;

        TokenBucket bucket = buckets.computeIfAbsent(key, ignored -> new TokenBucket(routeConfig.getBurstCapacity()));
        boolean allowed = bucket.tryConsume(routeConfig.getReplenishRate(), routeConfig.getBurstCapacity());

        Map<String, String> headers = Map.of(
                "X-RateLimit-Remaining", String.valueOf(bucket.availableTokens()),
                "X-RateLimit-Replenish-Rate", String.valueOf(routeConfig.getReplenishRate()),
                "X-RateLimit-Burst-Capacity", String.valueOf(routeConfig.getBurstCapacity())
        );

        return Mono.just(new Response(allowed, headers));
    }

    private void evictIdleBuckets() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis() > IDLE_EVICTION_MILLIS);
    }

    @PreDestroy
    void shutdown() {
        evictionExecutor.shutdownNow();
    }

    /** Field names deliberately match {@code RedisRateLimiter.Config} for symmetry. */
    public static class Config {
        private int replenishRate;
        private long burstCapacity;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public long getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(long burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }
    }

    /** A lock-per-key token bucket; refill happens lazily on each access. */
    private static final class TokenBucket {

        private long tokens;
        private long lastRefillNanos = System.nanoTime();
        private final AtomicLong lastAccessMillis = new AtomicLong(System.currentTimeMillis());

        TokenBucket(long initialTokens) {
            this.tokens = initialTokens;
        }

        synchronized boolean tryConsume(long replenishRatePerSecond, long capacity) {
            refill(replenishRatePerSecond, capacity);
            lastAccessMillis.set(System.currentTimeMillis());

            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        synchronized long availableTokens() {
            return tokens;
        }

        private void refill(long ratePerSecond, long capacity) {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            long tokensToAdd = (elapsedNanos * ratePerSecond) / 1_000_000_000L;

            if (tokensToAdd > 0) {
                lastRefillNanos = now;
                tokens = Math.min(capacity, tokens + tokensToAdd);
            }
        }

        long lastAccessMillis() {
            return lastAccessMillis.get();
        }
    }
}
