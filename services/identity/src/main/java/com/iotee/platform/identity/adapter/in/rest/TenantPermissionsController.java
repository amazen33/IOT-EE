package com.iotee.platform.identity.adapter.in.rest;

import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * {@code port.in.InvalidQueryException}; {@link RestExceptionAdvice} maps
 * them to HTTP 400.
 */
@RestController
public class TenantPermissionsController {

    private final GetTenantPermissionsUseCase getTenantPermissions;

    public TenantPermissionsController(GetTenantPermissionsUseCase getTenantPermissions) {
        this.getTenantPermissions = Objects.requireNonNull(getTenantPermissions, "getTenantPermissions");
    }

    @GetMapping("/tenants/{tenantId}/permissions")
    public ResponseEntity<TenantPermissionsResponse> getPermissions(
            @PathVariable("tenantId") String tenantId,
            @RequestParam("subjectId") String subjectId) {

        TenantPermissionsView view = getTenantPermissions.getTenantPermissions(toQuery(tenantId, subjectId));
        return ResponseEntity.ok(TenantPermissionsResponse.from(view));
    }

    /** Wire-to-port translation, kept separate so it reads as the REST half of the transport mapping. */
    static GetTenantPermissionsQuery toQuery(String tenantIdPathVariable, String subjectIdQueryParameter) {
        return new GetTenantPermissionsQuery(tenantIdPathVariable, subjectIdQueryParameter);
    }
}
