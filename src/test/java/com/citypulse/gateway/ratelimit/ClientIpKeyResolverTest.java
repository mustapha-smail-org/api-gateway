package com.citypulse.gateway.ratelimit;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpKeyResolverTest {

    private final ClientIpKeyResolver resolver = new ClientIpKeyResolver();

    @Test
    void resolvesTheDirectRemoteAddressWhenThereIsNoForwardedForHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events")
                        .remoteAddress(new InetSocketAddress("203.0.113.9", 54321)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.9");
    }

    @Test
    void trustsOnlyTheNearestForwardedForHopAndIgnoresClientSuppliedPrefix() {
        // "attacker-fake" is what a malicious client could prepend itself; Render's
        // edge proxy appends the address it actually saw ("203.0.113.9") as the
        // last entry. maxTrustedIndex(1) must resolve to the last entry, not the
        // attacker-controlled first one.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events")
                        .header("X-Forwarded-For", "attacker-fake, 203.0.113.9")
                        .remoteAddress(new InetSocketAddress("10.0.0.1", 443)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.9");
    }

    @Test
    void returnsUnknownRatherThanFailingWhenNoAddressCanBeDetermined() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("unknown");
    }
}
