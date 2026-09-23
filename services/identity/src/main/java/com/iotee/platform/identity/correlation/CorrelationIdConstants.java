package com.iotee.platform.identity.correlation;

/**
 * Constant names for propagating a correlation ID across transports.
 *
 * <p>Owned by {@code services/identity} alone (ADR 0013 Decision 1/6):
 * a future service that needs the same constants defines its own copy
 * in its own package, with the same values, rather than depending on
 * this class. Duplication across services is the accepted cost of
 * microservice autonomy, not an oversight to consolidate.
 */
public final class CorrelationIdConstants {

    /**
     * The canonical HTTP header name (ADR 0012 Decision 5). All-caps
     * "X-Correlation-ID", not "X-Correlation-Id" or "x-correlation-id" --
     * HTTP header names are case-insensitive on the wire, but this is the
     * spelling every log line, doc, and test in this platform uses.
     */
    public static final String HTTP_HEADER = "X-Correlation-ID";

    /** gRPC metadata key. gRPC metadata keys must be lowercase. */
    public static final String GRPC_METADATA_KEY = "x-correlation-id";

    /** Kafka message header key, matching {@link #HTTP_HEADER}'s spelling. */
    public static final String KAFKA_HEADER = "X-Correlation-ID";

    /** MDC key used when logging, so log lines carry the correlation ID. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationIdConstants() {
    }
}
