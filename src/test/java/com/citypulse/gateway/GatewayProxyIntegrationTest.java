package com.citypulse.gateway;

import com.citypulse.gateway.config.CorrelationIdGlobalFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Exercises the gateway end to end against a WireMock stand-in for
 * catalog-service. The circuit-breaker-opens scenario lives in its own test
 * class ({@link CircuitBreakerFallbackIntegrationTest}) so that tripping the
 * breaker there cannot leak state into the happy-path assertions here — Resilience4j
 * keeps circuit breaker instances (and their sliding windows) alive for the
 * lifetime of the Spring context, and contexts are cached/reused across test
 * methods within a class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class GatewayProxyIntegrationTest {

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

    @BeforeEach
    void resetStubs() {
        WIRE_MOCK.resetAll();
    }

    @Test
    void proxiesEventsRequestsToCatalogWithoutRewritingThePath() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[],\"nextCursor\":null,\"hasNext\":false}")));

        webTestClient.get().uri("/api/v1/events?period=THIS_WEEK&sort=START_DATE&limit=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.hasNext").isEqualTo(false);
    }

    @Test
    void generatesACorrelationIdAndForwardsTheSameValueToCatalog() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/categories"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));

        String responseCorrelationId = webTestClient.get().uri("/api/v1/categories")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(CorrelationIdGlobalFilter.HEADER_NAME)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER_NAME);

        assertMatchesUuid(responseCorrelationId);
        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/api/v1/categories"))
                .withHeader(CorrelationIdGlobalFilter.HEADER_NAME, equalTo(responseCorrelationId)));
    }

    @Test
    void echoesAClientSuppliedCorrelationIdRatherThanReplacingIt() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/categories"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));

        webTestClient.get().uri("/api/v1/categories")
                .header(CorrelationIdGlobalFilter.HEADER_NAME, "client-supplied-id-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationIdGlobalFilter.HEADER_NAME, "client-supplied-id-42");

        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/api/v1/categories"))
                .withHeader(CorrelationIdGlobalFilter.HEADER_NAME, equalTo("client-supplied-id-42")));
    }

    @Test
    void corsPreflightAllowsTheConfiguredOriginAndExposesTheCorrelationHeader() {
        webTestClient.options().uri("/api/v1/events")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, CorrelationIdGlobalFilter.HEADER_NAME);
    }

    @Test
    void proxiesFeedbackPostToCatalog() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/api/v1/feedback"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"status\":\"RECEIVED\"}")));

        webTestClient.post().uri("/api/v1/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"suggestion\",\"message\":\"Bravo\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RECEIVED");

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/feedback")));
    }

    @Test
    void proxiesEventReportPostToCatalog() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/api/v1/events/open-air-cinema-a1b2c3d4/reports"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"2\",\"status\":\"RECEIVED\"}")));

        webTestClient.post().uri("/api/v1/events/open-air-cinema-a1b2c3d4/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"date\",\"message\":\"Mauvaise date\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("2");

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/v1/events/open-air-cinema-a1b2c3d4/reports")));
    }

    @Test
    void proxiesAdminReportsListToCatalog() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/reports"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[],\"nextCursor\":null,\"hasNext\":false}")));

        webTestClient.get().uri("/api/v1/reports?page=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.hasNext").isEqualTo(false);

        WIRE_MOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/reports")));
    }

    @Test
    void retriesOnAServerErrorAndReturnsTheEventualSuccess() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/categories"))
                .inScenario("flaky-catalog")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("first retry"));
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/categories"))
                .inScenario("flaky-catalog")
                .whenScenarioStateIs("first retry")
                .willReturn(aResponse().withStatus(200).withBody("[\"Music\"]"))
                .willSetStateTo("recovered"));

        webTestClient.get().uri("/api/v1/categories")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0]").isEqualTo("Music");

        WIRE_MOCK.verify(2, getRequestedFor(urlEqualTo("/api/v1/categories")));
    }

    @Test
    void exhaustingRetriesReturnsAGatewayProblemBodyShapedLikeCatalogs() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v1/categories"))
                .willReturn(aResponse().withStatus(500)));

        webTestClient.get().uri("/api/v1/categories")
                .header(CorrelationIdGlobalFilter.HEADER_NAME, "contract-check-id")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.title").isEqualTo("Service temporarily unavailable")
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.correlationId").isEqualTo("contract-check-id")
                .jsonPath("$.timestamp").isNotEmpty();

        // 1 initial + 2 retries, all failing, then the gateway's own fallback —
        // never more than the configured retry budget.
        WIRE_MOCK.verify(3, getRequestedFor(urlEqualTo("/api/v1/categories")));
    }

    @Test
    void aggregatesCatalogsOpenApiDocumentUnderApiDocsCatalog() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"openapi\":\"3.1.0\"}")));

        webTestClient.get().uri("/api-docs/catalog")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isEqualTo("3.1.0");
    }

    private void assertMatchesUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) {
            throw new AssertionError("Expected a UUID-shaped correlation ID but got: " + value);
        }
    }
}
