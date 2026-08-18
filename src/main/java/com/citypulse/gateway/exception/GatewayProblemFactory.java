package com.citypulse.gateway.exception;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Builds RFC 9457 problem+json bodies for every response the gateway originates
 * itself (rate-limit denials, circuit-breaker fallbacks). Deliberately shaped to
 * match {@code catalog-service}'s {@code GlobalExceptionHandler} field-for-field
 * (top-level {@code code}/{@code timestamp}/{@code correlationId}, {@code type}
 * under the same {@code https://api.citypulse.dev/problems/} namespace) so the
 * frontend's {@code normaliseProblemDetail} cannot tell whether an error came from
 * catalog or from the gateway in front of it.
 */
@Component
@RequiredArgsConstructor
public class GatewayProblemFactory {

    private final Clock clock;

    public ProblemDetail create(
            HttpStatus status,
            GatewayErrorCode code,
            String title,
            String detail,
            String correlationId,
            String requestPath
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);

        problem.setTitle(title);
        problem.setType(URI.create("https://api.citypulse.dev/problems/" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        if (requestPath != null) {
            problem.setInstance(URI.create(requestPath));
        }

        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now(clock));
        problem.setProperty("correlationId", correlationId);

        return problem;
    }
}
