package com.iotee.platform.identity.adapter.in.rest;

import com.iotee.platform.identity.correlation.CorrelationIdConstants;
import com.iotee.platform.identity.correlation.CorrelationIdContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
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
 * core it adapts, as this service's own code. Since Track C step C1
 * it sits in {@code adapter.in.rest} (ADR 0017 Decision 2: the Spring
 * interceptor is part of the REST adapter; the framework-free context
 * stays in {@code correlation}). {@link WebMvcConfig}
 * registers this bean directly for this service -- there is no shared
 * auto-configuration to depend on, and a future service that needs the
 * same wiring authors its own copy of this class and its own
 * {@code WebMvcConfigurer}, per ADR 0013 Decision 6.
 *
 * <p><b>Trust boundary is configurable</b> (S4 fix): whether this
 * service treats every inbound HTTP request as arriving from OUTSIDE
 * the platform's trust zone, and therefore always mints a fresh
 * correlation ID, discarding any inbound
 * {@value CorrelationIdConstants#HTTP_HEADER} header, per {@link
 * CorrelationIdContext}'s origin/override rule, is controlled by the
 * {@code iotee.identity.correlation.trust-boundary} property, defaulting
 * to {@code true}. That default is correct TODAY, before APISIX exists:
 * with no gateway in front of it, every request this service receives
 * is, in effect, arriving at the edge. It stops being correct the day
 * APISIX (or an equivalent gateway) is deployed in front of this
 * service and becomes the actual trust boundary that mints and
 * overrides the ID; at that point this property should be set to
 * {@code false} in this service's own deployment configuration, so it
 * instead PROPAGATES the gateway-issued ID from the inbound header (see
 * {@link #preHandle}) rather than discarding it and minting a second,
 * different one, which would silently break end-to-end correlation the
 * moment a real edge is introduced. This class cannot know when that
 * day arrives; only deployment configuration can say so, which is
 * exactly why this was made a property instead of staying hardcoded
 * {@code true}.
 */
public class CorrelationIdHandlerInterceptor implements HandlerInterceptor {

    @Value("${iotee.identity.correlation.trust-boundary:true}")
    private boolean trustBoundary = true;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) {
        String inboundValue = trustBoundary ? null : request.getHeader(CorrelationIdConstants.HTTP_HEADER);
        String correlationId = CorrelationIdContext.adoptOrOrigin(inboundValue, trustBoundary);
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
