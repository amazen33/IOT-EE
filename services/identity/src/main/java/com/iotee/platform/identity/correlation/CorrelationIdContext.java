package com.iotee.platform.identity.correlation;

import java.util.UUID;

/**
 * Holds the correlation ID for the request/message currently being
 * processed on this thread (ADR 0012 Decision 5).
 *
 * <p><b>Framework-free by design</b>: this class has no dependency on
 * Spring, a servlet API, or any other framework -- it is pure {@code
 * java.lang}/{@code java.util}, usable from a plain Kafka consumer with
 * no Spring context just as easily as from an HTTP request. The Spring
 * MVC binding that calls into it, {@code CorrelationIdHandlerInterceptor},
 * lives in the REST adapter ({@code adapter.in.rest}) since Track C step
 * C1 (ADR 0017 Decision 2) -- an ordinary framework-coupled adapter
 * around this framework-free core, which driving adapters may use
 * alongside {@code port.in}. (A plain-servlet-filter alternative,
 * {@code CorrelationIdServletFilter}, existed briefly alongside it but
 * was deleted, unwired and untested, as dead code -- S5.)
 *
 * <p><b>Owned by {@code services/identity} alone</b> (ADR 0013 Decision
 * 1/2/6): before ADR 0013, this class lived in a shared {@code common}
 * module every service depended on, with its Spring MVC binding in a
 * separate shared {@code adapters/web-spring} module. ADR 0013 retired
 * both as shared runtime libraries -- all three classes now live
 * together in this one package, as this service's own code. A future
 * service that needs the same correlation-ID behavior authors its own
 * copy of all three classes in its own package -- duplicated
 * deliberately, per ADR 0013, never imported from here.
 *
 * <p><b>Origin and override rule</b> (the one rule every caller of this
 * class must follow): a correlation ID is issued exactly once, at the
 * platform's edge -- the APISIX gateway, or this class's {@link #origin()}
 * for a service that itself sits at an edge (e.g. a Kafka consumer with no
 * upstream APISIX hop). Every hop after that <em>propagates</em> the
 * existing value; it never mints a new one and never trusts a client- or
 * upstream-supplied value at the point of origin.
 *
 * <p>Concretely: if an inbound request already carries an
 * {@value CorrelationIdConstants#HTTP_HEADER} header, an edge component
 * OVERRIDES it with a freshly minted ID rather than honoring it -- a
 * caller cannot inject an arbitrary correlation ID into the platform's
 * own tracing. A non-edge, internal hop (service-to-service, or a
 * consumer reading a platform-produced Kafka message) DOES propagate an
 * inbound value, because at that point it is trusted platform-internal
 * state, not client input. {@link #adoptOrOrigin(String, boolean)} makes
 * this distinction explicit at the call site instead of leaving it
 * implicit in whichever caller happens to read the header first.
 *
 * <p><b>Async boundary handoff</b>: a {@link ThreadLocal} does not follow
 * work handed off to another thread (an executor task, a
 * {@code CompletableFuture} continuation, a reactive scheduler hop). Use
 * {@link #capture()} on the originating thread before the handoff and
 * {@link #restore(String)} at the start of the continuation running on
 * the new thread -- unlike {@link #set(String)}, {@link #restore(String)}
 * accepts {@code null} (meaning "no value was bound on the origin
 * thread") and clears rather than throws.
 */
public final class CorrelationIdContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationIdContext() {
    }

    /** Mints a brand new correlation ID. Used only at a trust boundary (see class Javadoc). */
    public static String origin() {
        return UUID.randomUUID().toString();
    }

    /**
     * Resolves the correlation ID to use for an inbound call, applying the
     * origin/override rule.
     *
     * @param inboundValue the value read from the inbound
     *     {@value CorrelationIdConstants#HTTP_HEADER} header/metadata/Kafka
     *     header, or {@code null}/blank if absent.
     * @param isTrustBoundary {@code true} when this call is arriving from
     *     outside the platform's trust zone (an external client through
     *     APISIX, or any other edge ingress) -- in which case
     *     {@code inboundValue} is ALWAYS discarded and a fresh one is
     *     minted. {@code false} for an internal, platform-to-platform hop,
     *     in which case {@code inboundValue} is propagated when present,
     *     and a fresh one is minted only if it is missing entirely (never
     *     expected in a well-formed internal call, but this method does
     *     not throw for it).
     * @return the correlation ID to use and propagate onward.
     */
    public static String adoptOrOrigin(String inboundValue, boolean isTrustBoundary) {
        if (isTrustBoundary) {
            return origin();
        }
        if (inboundValue == null || inboundValue.isBlank()) {
            return origin();
        }
        return inboundValue;
    }

    /** Binds {@code correlationId} to the current thread for the duration of this call/request. */
    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be null or blank");
        }
        CURRENT.set(correlationId);
    }

    /** Returns the correlation ID bound to the current thread, or {@code null} if none is bound. */
    public static String get() {
        return CURRENT.get();
    }

    /** Clears the correlation ID bound to the current thread. Must be called when a request/message finishes. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Snapshots the current thread's bound value (or {@code null} if none
     * is bound) for handoff to another thread. See the class Javadoc's
     * "Async boundary handoff" section.
     */
    public static String capture() {
        return CURRENT.get();
    }

    /**
     * Restores a value previously obtained from {@link #capture()} on the
     * thread now running a handed-off continuation. Unlike {@link #set},
     * a {@code null} {@code captured} value is accepted and clears the
     * binding, rather than throwing -- {@code null} legitimately means
     * "the origin thread had nothing bound."
     */
    public static void restore(String captured) {
        if (captured == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(captured);
        }
    }
}
