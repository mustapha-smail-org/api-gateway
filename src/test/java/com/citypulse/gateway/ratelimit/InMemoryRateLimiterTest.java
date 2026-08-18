package com.citypulse.gateway.ratelimit;

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
}
