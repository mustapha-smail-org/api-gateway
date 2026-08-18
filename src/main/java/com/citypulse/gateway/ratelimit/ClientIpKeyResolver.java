package com.citypulse.gateway.ratelimit;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Resolves the rate-limit key to the caller's IP address. Render (and any
 * reverse-proxy deployment) terminates TLS in front of the gateway, so
 * {@code exchange.getRequest().getRemoteAddress()} alone would resolve to the
 * proxy, not the client — {@link XForwardedRemoteAddressResolver} reads the
 * client address out of {@code X-Forwarded-For} instead.
 * <p>
 * {@code maxTrustedIndex(1)} trusts exactly one hop of {@code X-Forwarded-For}
 * (the platform's own edge proxy). A client cannot spoof its way past that: an
 * attacker-supplied {@code X-Forwarded-For} is prepended to, not replacing,
 * whatever the trusted proxy appends, so index 1 from the right is always the
 * proxy's own view of the connecting address.
 */
@Component
public class ClientIpKeyResolver implements KeyResolver {

    private final XForwardedRemoteAddressResolver delegate = XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = delegate.resolve(exchange);
        String ip = remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
        return Mono.just(ip);
    }
}
