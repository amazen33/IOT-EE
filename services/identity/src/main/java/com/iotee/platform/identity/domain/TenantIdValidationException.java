package com.iotee.platform.identity.domain;

/**
 * Thrown by {@link TenantId#of(String)} when a candidate tenant
 * identifier fails validation (missing the required
 * {@value TenantId#SYNTHETIC_PREFIX} prefix, or containing characters
 * outside the allowed format).
 *
 * <p>A narrow domain-specific subtype of {@link IllegalArgumentException}
 * rather than a bare {@code IllegalArgumentException} thrown directly, so
 * that the application layer ({@code GetTenantPermissionsService}) can
 * catch exactly this failure and rethrow it as the inbound port's
 * {@code InvalidQueryException} (which each driving adapter maps to its
 * own transport status), without a broad
 * {@code catch (IllegalArgumentException)} that would also swallow an
 * unrelated {@code IllegalArgumentException} thrown by, say, a bug
 * elsewhere in the request path. In M10+, once there are other real
 * domain validation failures, this is expected to become
 * {@code TenantIdValidationException extends DomainValidationException}
 * (a shared base type for this service's own domain validation
 * failures) rather than extending {@code IllegalArgumentException}
 * directly -- deferred until there is a second such exception to
 * generalize from.
 *
 * <p>This exception's own {@link #getMessage()} is safe to log (it is
 * useful to an operator) but is NOT safe to return to an HTTP client
 * as-is: it embeds {@link TenantId}'s validation regex (see
 * {@link TenantId#of(String)}), which is an internal implementation
 * detail, not API-contract information. Neither driving adapter echoes
 * it (or the wrapping exception's cause) to a client for exactly this
 * reason.
 */
public final class TenantIdValidationException extends IllegalArgumentException {

    public TenantIdValidationException(String message) {
        super(message);
    }
}
