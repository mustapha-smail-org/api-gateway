package com.citypulse.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Isolated in its own Spring context (separate {@code @SpringBootTest} class):
 * Resilience4j keeps the "catalog" {@code CircuitBreaker} instance's sliding
 * window alive for the lifetime of the application context, so deliberately
 * tripping it open here must not be able to leak into
 * {@link GatewayProxyIntegrationTest}'s happy-path assertions.
 * <p>
 * The default policy ({@code ResilienceConfiguration}) requires
 * {@code minimumNumberOfCalls=10} before the breaker evaluates its failure rate,
 * so this test issues 10 failing client requests, then asserts the 11th is
 * short-circuited — proving the specific invariant the route config depends on:
 * a request's internal retries (2 retries = up to 3 downstream calls each) count
 * as ONE call from the circuit breaker's point of view, because
 * {@code CircuitBreaker} is declared before {@code Retry} in the route's filter
 * list and therefore wraps it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class CircuitBreakerFallbackIntegrationTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(0);

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void catalogUri(DynamicPropertyRegistry registry) {
        WIRE_MOCK.start();
        registry.add("CATALOG_URI", () -> "http://localhost:" + WIRE_MOCK.port());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void opensAfterTenFailingClientRequestsAndThenShortCircuitsWithoutCallingCatalogAgain() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/categories"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/v1/categories")
                    .exchange()
                    .expectStatus().isEqualTo(503);
        }

        // 10 client requests x (1 initial + 2 retries) = 30 downstream calls,
        // recorded by the breaker as 10 calls, all failing -> breaker opens.
        WIRE_MOCK.verify(30, getRequestedFor(urlEqualTo("/api/v1/categories")));

        webTestClient.get().uri("/api/v1/categories")
                .exchange()
                .expectStatus().isEqualTo(503);

        // The breaker is open: this 11th request must be short-circuited before
        // it, or its retries, ever reach the (still stubbed-to-fail) downstream.
        WIRE_MOCK.verify(30, getRequestedFor(urlEqualTo("/api/v1/categories")));
    }
}
