package com.iotee.platform.identity.web;

import com.iotee.platform.identity.domain.TenantIdValidationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps this service's domain validation failures onto HTTP responses.
 *
 * <p>Without this class, {@link TenantIdValidationException} (a subtype
 * of {@link IllegalArgumentException} thrown by
 * {@code domain.TenantId#of}) propagated uncaught through
 * {@code DispatcherServlet} and surfaced as an unhandled-exception 500,
 * not the 400 a malformed client request should produce --
 * {@code TenantPermissionsControllerTest.nonSyntheticTenantIdIsRejected}
 * caught exactly this gap. {@code TenantPermissionsController}'s own
 * Javadoc previously (incorrectly) claimed Spring's default exception
 * handling already mapped this to a 400; it does not, for a plain
 * {@code IllegalArgumentException} subtype with no {@code @ResponseStatus}
 * of its own -- this class is what actually does that mapping.
 *
 * <p>Catches {@link TenantIdValidationException} specifically, not a
 * broad {@code IllegalArgumentException}, so an unrelated
 * {@code IllegalArgumentException} elsewhere in the request path is not
 * silently swallowed and reported as a client validation error it isn't.
 */
@RestControllerAdvice
public class DomainExceptionAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(DomainExceptionAdvice.class);

    @ExceptionHandler(TenantIdValidationException.class)
    public ResponseEntity<Map<String, String>> handleTenantIdValidationFailure(TenantIdValidationException ex) {
        // Log the real validation failure (useful to an operator, includes
        // the rejected value) but do NOT put ex.getMessage() in the HTTP
        // response body below: TenantId.of's message embeds the synthetic-
        // tenant-id validation regex (see TenantId's own Javadoc), and
        // returning it to the client would leak an internal implementation
        // detail as if it were API-contract information. If you are adding
        // detail to the response body, it must be a stable, deliberately
        // chosen string -- never ex.getMessage() "for extra helpfulness."
        LOG.warn("Rejected invalid tenant id: {}", ex.getMessage());

        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_failed",
                "detail", "request could not be validated"));
    }
}
