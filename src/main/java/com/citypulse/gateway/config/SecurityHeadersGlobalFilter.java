package com.citypulse.gateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Baseline response hardening headers. The gateway is a pure JSON API edge (no
 * browser-rendered HTML), so this is deliberately small — no Content-Security-Policy
 * script/style allow-listing is needed here, unlike a page-serving origin.
 */
@Component
public class SecurityHeadersGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Permissions-Policy", "geolocation=(), camera=(), microphone=()");

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // After correlation-ID resolution, otherwise ordering-independent.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
