package com.citypulse.gateway.config;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void propagatesAValidSuppliedCorrelationIdDownstreamAndOnTheResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events")
                        .header(CorrelationIdGlobalFilter.HEADER_NAME, "request-42:test"));
        AtomicReference<String> seenByDownstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(seenByDownstream)).block();

        assertThat(seenByDownstream).hasValue("request-42:test");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME))
                .isEqualTo("request-42:test");
        assertThat(exchange.getAttributes().get(CorrelationIdGlobalFilter.EXCHANGE_ATTRIBUTE))
                .isEqualTo("request-42:test");
    }

    @Test
    void replacesAMalformedSuppliedCorrelationIdWithAGeneratedUuid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events")
                        .header(CorrelationIdGlobalFilter.HEADER_NAME, "invalid value with spaces"));

        filter.filter(exchange, (ex) -> Mono.empty()).block();

        String responseValue = exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME);
        assertThat(responseValue).matches("[0-9a-f-]{36}");
    }

    @Test
    void generatesACorrelationIdWhenNoneIsSupplied() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));

        filter.filter(exchange, (ex) -> Mono.empty()).block();

        String responseValue = exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME);
        assertThat(responseValue).matches("[0-9a-f-]{36}");
    }

    private org.springframework.cloud.gateway.filter.GatewayFilterChain capturingChain(AtomicReference<String> capture) {
        return exchange -> {
            capture.set(exchange.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME));
            return Mono.empty();
        };
    }
}
