package com.iotee.platform.identity.port.in;

/**
 * Thrown by an inbound port when a query fails validation (ADR 0017
 * Decision 2).
 *
 * <p>Lives in {@code port.in}, not {@code domain}, on purpose: driving
 * adapters may depend on {@code port.in} only, so the port -- not the
 * domain -- has to name the failure they translate into a transport
 * status. The application layer catches the domain's own validation
 * exception and rethrows it as this one, keeping the domain exception as
 * the {@linkplain #getCause() cause} for operator logging.
 *
 * <p>{@link #getMessage()} is a short, fixed, client-safe summary. The
 * cause's message may embed internal detail (for example the tenant-id
 * validation pattern); adapters log it but must never return it to a
 * client.
 */
public final class InvalidQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidQueryException(String clientSafeMessage, Throwable cause) {
        super(clientSafeMessage, cause);
    }
}
