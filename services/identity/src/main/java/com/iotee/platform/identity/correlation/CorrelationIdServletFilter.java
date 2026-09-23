package com.iotee.platform.identity.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;

/**
 * Binds a correlation ID to {@link CorrelationIdContext} (and SLF4J's
 * MDC) for the lifetime of one HTTP request, per ADR 0012 Decision 5.
 *
 * <p>An alternative to {@link CorrelationIdHandlerInterceptor} for a
 * non-Spring-MVC deployment that still runs on a servlet container --
 * same behavior, plain {@code jakarta.servlet} filter instead of a
 * Spring interceptor. This service does not currently wire this filter
 * (it uses the interceptor via {@link WebMvcConfig}); it is kept as the
 * documented alternative for a future service shaped differently. Lives
 * beside the interceptor, not in a separate shared module -- ADR 0013
 * retired the shared-module split this class used to live behind.
 */
@WebFilter(urlPatterns = "/*")
public class CorrelationIdServletFilter extends HttpFilter {

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String correlationId = CorrelationIdContext.adoptOrOrigin(null, true);
        CorrelationIdContext.set(correlationId);
        MDC.put(CorrelationIdConstants.MDC_KEY, correlationId);
        response.setHeader(CorrelationIdConstants.HTTP_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIdConstants.MDC_KEY);
            CorrelationIdContext.clear();
        }
    }
}
