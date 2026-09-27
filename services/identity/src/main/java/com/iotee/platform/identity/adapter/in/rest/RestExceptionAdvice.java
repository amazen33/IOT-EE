package com.iotee.platform.identity.adapter.in.rest;

import com.iotee.platform.identity.port.in.AuthenticationFailedException;
import com.iotee.platform.identity.port.in.AuthorizationDeniedException;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps inbound-port failures onto HTTP status for the REST adapter:
 * {@link InvalidQueryException} to 400, {@link AuthenticationFailedException}
 * to 401, {@link AuthorizationDeniedException} to 403. Formerly
 * {@code web.DomainExceptionAdvice}, which caught the domain's
 * {@code TenantIdValidationException} directly; after step C1 the REST
 * adapter depends on {@code port.in} only, so it catches the port's own
 * exceptions instead (the application layer wraps the underlying failure
 * as each one's cause).
 *
 * <p>Catches each exception type specifically, never a broad
 * {@link RuntimeException}, so an unrelated bug elsewhere in the request
 * path is not reported as a client-facing validation/auth failure.
 *
 * <p>Every response body is fixed text. The exception (and especially its
 * cause -- a validation pattern, a {@code TokenRejectedException.Reason},
 * a {@code PolicyDecision}'s reason and grant/rule id) may carry internal
 * detail; that is logged for operators and never echoed to the client. In
 * particular the 401 and 403 bodies are deliberately identical in shape and
 * say nothing about WHY authentication or authorization failed, so a
 * client cannot distinguish "no such tenant", "your token's audience is
 * wrong" and "you hold no grant here" from the response alone.
 */
@RestControllerAdvice
public class RestExceptionAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(RestExceptionAdvice.class);

    static final String ERROR_CODE = "validation_failed";
    static final String CLIENT_SAFE_DETAIL = "request could not be validated";
    static final String AUTHENTICATION_ERROR_CODE = "authentication_failed";
    static final String AUTHENTICATION_CLIENT_SAFE_DETAIL = "authentication required";
    static final String AUTHORIZATION_ERROR_CODE = "authorization_denied";
    static final String AUTHORIZATION_CLIENT_SAFE_DETAIL = "access denied";

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuery(InvalidQueryException ex) {
        Throwable detail = ex.getCause() != null ? ex.getCause() : ex;
        LOG.warn("Rejected invalid REST request: {}", detail.getMessage());

        return ResponseEntity.badRequest().body(Map.of(
                "error", ERROR_CODE,
                "detail", CLIENT_SAFE_DETAIL));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationFailed(AuthenticationFailedException ex) {
        Throwable detail = ex.getCause() != null ? ex.getCause() : ex;
        LOG.warn("Rejected unauthenticated REST request: {}", detail.getMessage());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", AUTHENTICATION_ERROR_CODE,
                "detail", AUTHENTICATION_CLIENT_SAFE_DETAIL));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        LOG.warn("Denied REST request: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", AUTHORIZATION_ERROR_CODE,
                "detail", AUTHORIZATION_CLIENT_SAFE_DETAIL));
    }
}
