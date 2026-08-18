package com.citypulse.gateway;

import com.citypulse.gateway.config.CorrelationIdThreadLocalAccessor;
import com.citypulse.gateway.config.GatewayProperties;
import io.micrometer.context.ContextRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import reactor.core.publisher.Hooks;

@EnableConfigurationProperties(GatewayProperties.class)
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        /*
         * MDC is thread-bound and does not survive WebFlux's operator-hop-per-thread
         * execution model. Registering the correlation-ID key here, before any
         * reactive pipeline runs, lets CorrelationIdGlobalFilter write it into the
         * Reactor Context (contextWrite) and have it reappear in MDC around every
         * operator on that chain — which is what makes `%X{correlationId}` in
         * logback-spring.xml work the same way it does in the servlet-based
         * catalog-service.
         */
        ContextRegistry.getInstance().registerThreadLocalAccessor(new CorrelationIdThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();

        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
