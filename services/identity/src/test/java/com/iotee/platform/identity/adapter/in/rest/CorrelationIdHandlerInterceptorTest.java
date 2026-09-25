package com.iotee.platform.identity.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iotee.platform.identity.correlation.CorrelationIdConstants;
import com.iotee.platform.identity.correlation.CorrelationIdContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

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
    void preHandleStillOverridesAnInboundHeaderValueWhenTrustBoundaryDefaultsTrue() {
        // S4: trustBoundary defaults to true (the field initializer),
        // exactly like the previously-hardcoded behavior this test
        // predates -- this is the regression guard for that default.
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdConstants.HTTP_HEADER)).thenReturn("client-supplied-value");
        HttpServletResponse response = mock(HttpServletResponse.class);

        interceptor.preHandle(request, response, new Object());

        String bound = CorrelationIdContext.get();
        assertNotEquals("client-supplied-value", bound,
                "trustBoundary=true (the default) must still discard an inbound header value");
    }

    @Test
    void preHandlePropagatesAnInboundHeaderValueWhenTrustBoundaryIsFalse() {
        // S4: once this service sits behind a real edge (APISIX or
        // equivalent) and iotee.identity.correlation.trust-boundary is
        // set to false, an inbound header value must be PROPAGATED, not
        // discarded -- otherwise setting the property to false would
        // silently do nothing and end-to-end correlation would still
        // break the moment a real edge exists.
        ReflectionTestUtils.setField(interceptor, "trustBoundary", false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdConstants.HTTP_HEADER)).thenReturn("upstream-issued-value");
        HttpServletResponse response = mock(HttpServletResponse.class);

        interceptor.preHandle(request, response, new Object());

        assertEquals("upstream-issued-value", CorrelationIdContext.get(),
                "trustBoundary=false must propagate the inbound header value rather than minting a new one");
        verify(response).setHeader(CorrelationIdConstants.HTTP_HEADER, "upstream-issued-value");
    }

    @Test
    void preHandleStillMintsAFreshIdWhenTrustBoundaryIsFalseButNoHeaderIsPresent() {
        // trustBoundary=false must not fail merely because no upstream
        // hop supplied a value (CorrelationIdContext.adoptOrOrigin falls
        // back to origin() for a blank/absent inboundValue regardless of
        // isTrustBoundary).
        ReflectionTestUtils.setField(interceptor, "trustBoundary", false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdConstants.HTTP_HEADER)).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        interceptor.preHandle(request, response, new Object());

        assertNotEquals(null, CorrelationIdContext.get());
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
