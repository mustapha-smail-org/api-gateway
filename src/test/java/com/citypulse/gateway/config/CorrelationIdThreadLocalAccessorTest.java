package com.citypulse.gateway.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdThreadLocalAccessorTest {

    private final CorrelationIdThreadLocalAccessor accessor = new CorrelationIdThreadLocalAccessor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keyIsTheCorrelationIdMdcKey() {
        assertThat(accessor.key()).isEqualTo(CorrelationIdThreadLocalAccessor.KEY);
        assertThat(CorrelationIdThreadLocalAccessor.KEY).isEqualTo("correlationId");
    }

    @Test
    void getValueReadsFromTheMdc() {
        MDC.put(CorrelationIdThreadLocalAccessor.KEY, "abc-123");

        assertThat(accessor.getValue()).isEqualTo("abc-123");
    }

    @Test
    void getValueReturnsNullWhenNothingIsSet() {
        assertThat(accessor.getValue()).isNull();
    }

    @Test
    void setValueWritesToTheMdc() {
        accessor.setValue("def-456");

        assertThat(MDC.get(CorrelationIdThreadLocalAccessor.KEY)).isEqualTo("def-456");
    }

    @Test
    void resetRemovesTheMdcEntry() {
        MDC.put(CorrelationIdThreadLocalAccessor.KEY, "to-be-cleared");

        accessor.reset();

        assertThat(MDC.get(CorrelationIdThreadLocalAccessor.KEY)).isNull();
    }
}
