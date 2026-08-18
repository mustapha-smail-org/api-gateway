package com.citypulse.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersGlobalFilterTest {

    private final SecurityHeadersGlobalFilter filter = new SecurityHeadersGlobalFilter();

    @Test
    void addsHardeningHeadersToEveryResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Permissions-Policy")).contains("geolocation=()");
    }
}
