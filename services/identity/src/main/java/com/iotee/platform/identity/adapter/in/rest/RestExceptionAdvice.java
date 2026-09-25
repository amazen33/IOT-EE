package com.iotee.platform.identity.adapter.in.rest;

import com.iotee.platform.identity.port.in.InvalidQueryException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps inbound-port validation failures onto HTTP 400 for the REST
 * adapter. Formerly {@code web.DomainExceptionAdvice}, which caught the
 * domain's {@code TenantIdValidationException} directly; after step C1
 * the REST adapter depends on {@code port.in} only, so it catches the
 * port's {@link InvalidQueryException} instead (the application layer
 * wraps the domain exception as its cause).
 *
 * <p>Catches {@link InvalidQueryException} specifically, never a broad
 * {@link IllegalArgumentException}, so an unrelated bug elsewhere in the
 * request path is not reported as a client validation error.
 *
 * <p>The response body is fixed text. The exception (and especially its
 * cause) may carry internal detail such as the tenant-id validation
 * pattern; that is logged for operators and never echoed to the client.
 */
@RestControllerAdvice
public class RestExceptionAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(RestExceptionAdvice.class);

    static final String ERROR_CODE = "validation_failed";
    static final String CLIENT_SAFE_DETAIL = "request could not be validated";

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuery(InvalidQueryException ex) {
        Throwable detail = ex.getCause() != null ? ex.getCause() : ex;
        LOG.warn("Rejected invalid REST request: {}", detail.getMessage());

        return ResponseEntity.badRequest().body(Map.of(
                "error", ERROR_CODE,
                "detail", CLIENT_SAFE_DETAIL));
    }
}
