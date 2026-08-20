package com.citypulse.gateway.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.citypulse.gateway.config.CorrelationIdGlobalFilter;
import com.citypulse.gateway.exception.GatewayProblemFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingGlobalFilterTest {

    // ProblemDetail only serializes its "code"/"timestamp"/"correlationId"
    // extensions to top-level JSON keys via Spring's ProblemDetailJacksonMixin.
    // Production wiring gets this for free by injecting Boot's autoconfigured
    // JsonMapper bean; this test applies the mixin by hand to match.
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
    private final GatewayProblemFactory problemFactory =
            new GatewayProblemFactory(Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void allowsTheRequestThroughWhenTheLimiterPermitsIt() {
        InMemoryRateLimiter permissiveLimiter = new InMemoryRateLimiter(10, 10, null);
        RateLimitingGlobalFilter filter = new RateLimitingGlobalFilter(
                permissiveLimiter, fixedKey("1.2.3.4"), problemFactory, jsonMapper);
        MockServerWebExchange exchange = exchangeFor("/api/v1/events");

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block();

        assertThat(chainInvoked[0]).isTrue();
    }

    @Test
    void denialsAreShapedAs429ProblemJsonMatchingCatalogsErrorContract() {
        InMemoryRateLimiter exhaustedLimiter = new InMemoryRateLimiter(0, 0, null);
        RateLimitingGlobalFilter filter = new RateLimitingGlobalFilter(
                exhaustedLimiter, fixedKey("1.2.3.4"), problemFactory, jsonMapper);
        MockServerWebExchange exchange = exchangeFor("/api/v1/events");
        exchange.getAttributes().put(CorrelationIdGlobalFilter.EXCHANGE_ATTRIBUTE, "rate-limit-test-id");

        filter.filter(exchange, ex -> {
            throw new AssertionError("chain must not run when the limiter denies the request");
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();

        // Same field set the frontend's normaliseProblemDetail() reads off any
        // gateway- or catalog-originated error: status, top-level code/title/
        // detail/correlationId.
        JsonNode body = readJsonBody(exchange);
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("correlationId").asText()).isEqualTo("rate-limit-test-id");
    }

    private JsonNode readJsonBody(MockServerWebExchange exchange) {
        try {
            org.springframework.mock.http.server.reactive.MockServerHttpResponse response =
                    (org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse();
            String body = response.getBodyAsString().block();
            return jsonMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    private KeyResolver fixedKey(String key) {
        return exchange -> Mono.just(key);
    }
}
