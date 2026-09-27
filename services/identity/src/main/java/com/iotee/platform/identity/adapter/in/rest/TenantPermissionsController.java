package com.iotee.platform.identity.adapter.in.rest;

import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST driving adapter (ADR 0017 Decision 2, {@code adapter.in.rest}):
 * {@code GET /tenants/{tenantId}/permissions?subjectId=...}.
 *
 * <p>Translation only, no business logic: the path variable and query
 * parameter become a {@link GetTenantPermissionsQuery}, which goes to the
 * injected {@link GetTenantPermissionsUseCase}; the returned
 * {@link TenantPermissionsView} becomes a {@link TenantPermissionsResponse}.
 * The gRPC adapter ({@code adapter.in.grpc}) builds the identical query
 * from its own request message (ADR 0017 Decision 3), and the two
 * adapters never reference each other -- both enforced by
 * {@code IdentityHexagonalArchitectureRulesTest}.
 *
 * <p>Validation failures surface from the use case as
 * {@code port.in.InvalidQueryException} (HTTP 400), authentication
 * failures as {@code port.in.AuthenticationFailedException} (HTTP 401),
 * and authorization denials as {@code port.in.AuthorizationDeniedException}
 * (HTTP 403); {@link RestExceptionAdvice} maps all three.
 *
 * <p>The caller's bearer token is read from the standard {@code Authorization}
 * header ({@code "Bearer <token>"}, case-insensitively on the scheme) and
 * nothing else -- in particular, never from any gateway-supplied identity
 * header (ADR 0016 Decision 4). Authentication itself (signature, claims,
 * audience) happens once, in the application layer, never here: this
 * adapter only strips the scheme prefix, exactly the same translation-only
 * role it already has for {@code tenantId} and {@code subjectId}.
 */
@RestController
public class TenantPermissionsController {

    /** The bearer scheme prefix, matched case-insensitively per RFC 6750 section 2.1. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final GetTenantPermissionsUseCase getTenantPermissions;

    public TenantPermissionsController(GetTenantPermissionsUseCase getTenantPermissions) {
        this.getTenantPermissions = Objects.requireNonNull(getTenantPermissions, "getTenantPermissions");
    }

    @GetMapping("/tenants/{tenantId}/permissions")
    public ResponseEntity<TenantPermissionsResponse> getPermissions(
            @PathVariable("tenantId") String tenantId,
            @RequestParam("subjectId") String subjectId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        TenantPermissionsView view =
                getTenantPermissions.getTenantPermissions(toQuery(tenantId, subjectId, authorizationHeader));
        return ResponseEntity.ok(TenantPermissionsResponse.from(view));
    }

    /** Wire-to-port translation, kept separate so it reads as the REST half of the transport mapping. */
    static GetTenantPermissionsQuery toQuery(
            String tenantIdPathVariable, String subjectIdQueryParameter, String authorizationHeader) {
        return new GetTenantPermissionsQuery(tenantIdPathVariable, subjectIdQueryParameter, bearerToken(authorizationHeader));
    }

    /** Strips the {@code "Bearer "} scheme prefix; absent or non-Bearer becomes empty, never null. */
    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return "";
        }
        if (authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return "";
    }
}
