package com.citypulse.gateway.config;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Bridges the {@code correlationId} Reactor Context entry written by
 * {@link CorrelationIdGlobalFilter} back onto the logging {@link MDC} for the
 * duration of each operator invocation on that chain. Without this, log lines
 * emitted from inside a WebFlux pipeline never see the correlation ID, because
 * MDC is thread-local and WebFlux hops threads between operators.
 */
public class CorrelationIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "correlationId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(KEY, value);
    }

    @Override
    public void reset() {
        MDC.remove(KEY);
    }
}
