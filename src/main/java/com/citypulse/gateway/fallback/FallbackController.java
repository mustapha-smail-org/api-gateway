package com.citypulse.gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.citypulse.gateway.config.CorrelationIdGlobalFilter;
import com.citypulse.gateway.exception.GatewayErrorCode;
import com.citypulse.gateway.exception.GatewayProblemFactory;

import lombok.RequiredArgsConstructor;

/**
 * Target of every route's {@code CircuitBreaker} filter {@code fallbackUri}
 * ({@code forward:/__fallback/{service}}). Reached when a downstream is
 * unreachable, too slow, or the breaker is open after repeated failures.
 * <p>
 * Returns 503, not some other 5xx: {@code frontend/src/shared/api/errors.ts}
 * treats {@code status === 503} as retriable, so TanStack Query backs off and
 * retries automatically — the correct behaviour against a cold-starting or
 * momentarily-down downstream, rather than surfacing a hard error to the user.
 */
@RestController
@RequiredArgsConstructor
public class FallbackController {

    private final GatewayProblemFactory problemFactory;

    @GetMapping("/__fallback/{service}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable String service, ServerWebExchange exchange) {
        String correlationId = (String) exchange.getAttributes().get(CorrelationIdGlobalFilter.EXCHANGE_ATTRIBUTE);

        ProblemDetail body = problemFactory.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                GatewayErrorCode.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable",
                "The %s service is temporarily unavailable.".formatted(service),
                correlationId,
                exchange.getRequest().getPath().value()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
