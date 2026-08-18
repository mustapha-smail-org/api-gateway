package com.citypulse.gateway.config;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * The gateway is the first hop, so it owns correlation-ID origination: this filter
 * resolves one value per request, forwards it to every downstream call, and echoes
 * it on the gateway's own response — including for gateway-originated responses
 * (rate-limit 429s, circuit-breaker fallback 503s) that never reach a downstream
 * service.
 * <p>
 * The acceptance rule mirrors {@code catalog-service}'s
 * {@code CorrelationIdFilter} exactly, so a client-supplied ID that is valid for
 * catalog is valid here too, and one that catalog would reject and replace is
 * rejected and replaced here first — the two services never disagree about
 * whether a supplied ID was usable.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String EXCHANGE_ATTRIBUTE = "correlationId";

    private static final Pattern VALID_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER_NAME));

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HEADER_NAME, correlationId)
                .build();

        exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);
        exchange.getAttributes().put(EXCHANGE_ATTRIBUTE, correlationId);

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        return chain.filter(mutatedExchange)
                .contextWrite(context -> context.put(CorrelationIdThreadLocalAccessor.KEY, correlationId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolve(String suppliedValue) {
        if (suppliedValue != null && VALID_VALUE.matcher(suppliedValue).matches()) {
            return suppliedValue;
        }
        return UUID.randomUUID().toString();
    }
}
