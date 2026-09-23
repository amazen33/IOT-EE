package com.iotee.platform.identity.correlation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC {@link HandlerInterceptor} that binds a correlation ID to
 * {@link CorrelationIdContext} (and SLF4J's MDC) for one HTTP request,
 * per ADR 0012 Decision 5.
 *
 * <p>This is the Spring adapter for this package's framework-free
 * {@link CorrelationIdContext} -- before ADR 0013, it lived in a
 * separate shared {@code adapters/web-spring} module (because it
 * implements a Spring type and touches the servlet API, both forbidden
 * under {@code common}'s old framework-freedom rule); ADR 0013 retired
 * that shared module, so this class now lives beside the framework-free
 * core it adapts, as this service's own code. {@link WebMvcConfig}
 * registers this bean directly for this service -- there is no shared
 * auto-configuration to depend on, and a future service that needs the
 * same wiring authors its own copy of this class and its own
 * {@code WebMvcConfigurer}, per ADR 0013 Decision 6.
 *
 * <p>Same trust-boundary rule as {@link CorrelationIdContext}'s Javadoc:
 * any inbound {@value CorrelationIdConstants#HTTP_HEADER} header is
 * discarded, never honored, and a fresh ID is always minted, because
 * APISIX is the platform's edge and every services/* module sits behind
 * it. Deliberately NOT the W3C {@code traceparent} header or its
 * semantics -- {@code X-Correlation-ID} is this platform's own, simpler,
 * business-level correlation identifier, tracked independently of
 * whatever distributed-tracing propagation (if any) also runs alongside
 * it.
 */
public class CorrelationIdHandlerInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) {
        String correlationId = CorrelationIdContext.adoptOrOrigin(null, true);
        CorrelationIdContext.set(correlationId);
        MDC.put(CorrelationIdConstants.MDC_KEY, correlationId);
        response.setHeader(CorrelationIdConstants.HTTP_HEADER, correlationId);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler, Exception ex) {
        MDC.remove(CorrelationIdConstants.MDC_KEY);
        CorrelationIdContext.clear();
    }
}
