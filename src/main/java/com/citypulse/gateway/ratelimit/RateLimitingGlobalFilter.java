package com.citypulse.gateway.ratelimit;

import java.nio.charset.StandardCharsets;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.citypulse.gateway.config.CorrelationIdGlobalFilter;
import com.citypulse.gateway.exception.GatewayErrorCode;
import com.citypulse.gateway.exception.GatewayProblemFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Rate limiting is implemented as a {@link GlobalFilter} rather than Spring Cloud
 * Gateway's built-in {@code RequestRateLimiter} per-route filter factory: that
 * factory denies a request by calling {@code setComplete()} on the response
 * directly, which commits it with no body before any later filter gets a chance to
 * write a problem+json payload onto it. Owning the whole deny path in one filter —
 * resolve key, check the limiter, write status/headers/body together — avoids that
 * race and keeps every gateway-originated error in the same shape, checked once
 * per incoming request regardless of which route it would have matched.
 */
@Component
@RequiredArgsConstructor
public class RateLimitingGlobalFilter implements GlobalFilter, Ordered {

    /**
     * No per-route rate-limit tiers in v1: every route shares one bucket per client
     * key, so a single constant "route" is enough for {@link RateLimiter}'s
     * per-route config map.
     */
    private static final String ROUTE_ID = "global";

    private final RateLimiter<?> gatewayRateLimiter;
    private final KeyResolver clientIpKeyResolver;
    private final GatewayProblemFactory problemFactory;
    private final JsonMapper jsonMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return clientIpKeyResolver.resolve(exchange)
                .flatMap(key -> gatewayRateLimiter.isAllowed(ROUTE_ID, key))
                .flatMap(response -> {
                    response.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().add(name, value));

                    if (response.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    return tooManyRequests(exchange);
                });
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        String correlationId = (String) exchange.getAttributes().get(CorrelationIdGlobalFilter.EXCHANGE_ATTRIBUTE);

        ProblemDetail body = problemFactory.create(
                HttpStatus.TOO_MANY_REQUESTS,
                GatewayErrorCode.RATE_LIMIT_EXCEEDED,
                "Too many requests",
                "You have sent too many requests. Please slow down and try again shortly.",
                correlationId,
                exchange.getRequest().getPath().value()
        );

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // Advisory only (no distributed knowledge of exact refill timing); tells a
        // well-behaved client roughly when it is worth retrying.
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");

        byte[] bytes = serialize(body);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serialize(ProblemDetail body) {
        try {
            return jsonMapper.writeValueAsBytes(body);
        } catch (JacksonException e) {
            // Extremely unlikely (ProblemDetail is always plain-data), but must
            // never propagate an exception out of a filter that is itself handling
            // a deny path.
            return "{\"title\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public int getOrder() {
        // After correlation-ID/security-header filters, comfortably before routing.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
