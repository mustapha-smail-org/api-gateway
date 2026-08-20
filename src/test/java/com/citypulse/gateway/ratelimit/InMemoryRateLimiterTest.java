package com.citypulse.gateway.ratelimit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(10, 3, null);

    @Test
    void allowsRequestsUpToTheBurstCapacity() {
        assertThat(limiter.isAllowed("global", "1.2.3.4").block().isAllowed()).isTrue();
        assertThat(limiter.isAllowed("global", "1.2.3.4").block().isAllowed()).isTrue();
        assertThat(limiter.isAllowed("global", "1.2.3.4").block().isAllowed()).isTrue();
    }

    @Test
    void deniesOnceTheBurstCapacityIsExhausted() {
        limiter.isAllowed("global", "1.2.3.4").block();
        limiter.isAllowed("global", "1.2.3.4").block();
        limiter.isAllowed("global", "1.2.3.4").block();

        assertThat(limiter.isAllowed("global", "1.2.3.4").block().isAllowed()).isFalse();
    }

    @Test
    void tracksEachKeyIndependently() {
        limiter.isAllowed("global", "1.2.3.4").block();
        limiter.isAllowed("global", "1.2.3.4").block();
        limiter.isAllowed("global", "1.2.3.4").block();
        // "1.2.3.4" is now exhausted, but a different client key must not be
        // affected by it.
        assertThat(limiter.isAllowed("global", "5.6.7.8").block().isAllowed()).isTrue();
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        InMemoryRateLimiter fastRefill = new InMemoryRateLimiter(1000, 1, null);

        assertThat(fastRefill.isAllowed("global", "1.2.3.4").block().isAllowed()).isTrue();
        assertThat(fastRefill.isAllowed("global", "1.2.3.4").block().isAllowed()).isFalse();

        // 1000 tokens/second replenish rate: well over one token available after 10ms.
        Thread.sleep(10);

        assertThat(fastRefill.isAllowed("global", "1.2.3.4").block().isAllowed()).isTrue();
    }

    @Test
    void responseHeadersReportRemainingTokensAndConfiguredLimits() {
        var response = limiter.isAllowed("global", "1.2.3.4").block();

        assertThat(response.getHeaders())
                .containsEntry("X-RateLimit-Remaining", "2")
                .containsEntry("X-RateLimit-Replenish-Rate", "10")
                .containsEntry("X-RateLimit-Burst-Capacity", "3");
    }

    @Test
    void fallsBackToTheDefaultConfigWhenNoPerRouteConfigIsRegistered() {
        // getConfig() (inherited from AbstractStatefulConfigurable) starts empty in
        // this unit test, so every call must resolve against the constructor's
        // default rather than throwing or silently allowing everything.
        assertThat(limiter.getConfig()).isEmpty();
        assertThat(limiter.isAllowed("unregistered-route", "9.9.9.9").block().isAllowed()).isTrue();
    }

    @Test
    void evictsBucketsThatHaveBeenIdlePastTheThreshold() throws Exception {
        limiter.isAllowed("global", "1.2.3.4").block();

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> buckets =
                (ConcurrentHashMap<String, Object>) readField(limiter, "buckets");
        assertThat(buckets).containsKey("global/1.2.3.4");

        // Backdate the bucket's last-access timestamp beyond the 10-minute idle window,
        // then trigger the (normally scheduled) eviction sweep directly.
        Object bucket = buckets.get("global/1.2.3.4");
        backdateLastAccess(bucket);
        invoke(limiter, "evictIdleBuckets");

        assertThat(buckets).doesNotContainKey("global/1.2.3.4");
    }

    @Test
    void keepsBucketsThatAreStillActive() throws Exception {
        limiter.isAllowed("global", "1.2.3.4").block();

        // A fresh bucket must survive an eviction sweep.
        invoke(limiter, "evictIdleBuckets");

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> buckets =
                (ConcurrentHashMap<String, Object>) readField(limiter, "buckets");
        assertThat(buckets).containsKey("global/1.2.3.4");
    }

    @Test
    void shutdownStopsTheEvictionExecutor() throws Exception {
        InMemoryRateLimiter disposable = new InMemoryRateLimiter(10, 3, null);

        invoke(disposable, "shutdown");

        var executor = (java.util.concurrent.ExecutorService) readField(disposable, "evictionExecutor");
        assertThat(executor.isShutdown()).isTrue();
    }

    /** Forces the given TokenBucket's lastAccessMillis far enough into the past to be evictable. */
    private static void backdateLastAccess(Object tokenBucket) throws Exception {
        Field field = tokenBucket.getClass().getDeclaredField("lastAccessMillis");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong last =
                (java.util.concurrent.atomic.AtomicLong) field.get(tokenBucket);
        last.set(System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(15));
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }
}
