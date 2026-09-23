package com.iotee.platform.identity.correlation;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdHandlerInterceptorTest {

    private final CorrelationIdHandlerInterceptor interceptor = new CorrelationIdHandlerInterceptor();

    @AfterEach
    void clearThreadState() {
        CorrelationIdContext.clear();
        MDC.clear();
    }

    @Test
    void preHandleOverridesAnInboundHeaderValueRatherThanHonoringIt() {
        // The interceptor itself never reads the inbound header (see
        // CorrelationIdContext's Javadoc: preHandle always calls
        // adoptOrOrigin(null, true), i.e. always a trust boundary) -- this
        // test documents that behavior by asserting the bound value is
        // never a value a client could have supplied through the request.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertTrue(proceed, "preHandle must allow the request to proceed");
        String bound = CorrelationIdContext.get();
        assertNotEquals(null, bound);
        assertNotEquals("client-supplied-value", bound);
        verify(response).setHeader(CorrelationIdConstants.HTTP_HEADER, bound);
    }

    @Test
    void preHandleBindsToMdcSoLogLinesCarryTheCorrelationId() {
        // This test needs a real SLF4J provider on the test classpath (see
        // this module's pom.xml: logback-classic, test scope). Without one,
        // org.slf4j.MDC falls back to the NOP logger's MDC implementation,
        // and MDC.get(...) returns null regardless of what MDC.put(...)
        // stored -- this test would then fail even though the interceptor's
        // own binding logic is correct. If this starts failing again with
        // "No SLF4J providers were found" in the log, check the pom.xml
        // dependency was not removed as apparently-unused.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        interceptor.preHandle(request, response, new Object());

        String mdcValue = MDC.get(CorrelationIdConstants.MDC_KEY);
        assertNotEquals(null, mdcValue);
        assertNotEquals("", mdcValue);
    }

    @Test
    void afterCompletionClearsBothContextAndMdc() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        interceptor.preHandle(request, response, new Object());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(CorrelationIdContext.get());
        assertNull(MDC.get(CorrelationIdConstants.MDC_KEY));
    }

    @Test
    void eachRequestGetsItsOwnCorrelationId() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        interceptor.preHandle(request, response, new Object());
        String first = CorrelationIdContext.get();
        interceptor.afterCompletion(request, response, new Object(), null);

        interceptor.preHandle(request, response, new Object());
        String second = CorrelationIdContext.get();

        assertNotEquals(first, second, "two separate requests must not share a correlation id");
    }
}
