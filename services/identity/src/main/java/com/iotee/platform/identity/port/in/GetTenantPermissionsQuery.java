package com.iotee.platform.identity.port.in;

import java.util.Objects;

/**
 * Inbound query: "which permissions does {@code subjectId} hold in
 * {@code tenantId}, as read by the caller identified in {@code bearerToken}?"
 * (ADR 0017 Decision 2, {@code port.in}).
 *
 * <p>This record is the single meeting point of every driving adapter.
 * The REST adapter ({@code adapter.in.rest}) and the gRPC adapter
 * ({@code adapter.in.grpc}) each translate their own wire format into
 * exactly this record and hand it to {@link GetTenantPermissionsUseCase}
 * (ADR 0017 Decision 3: dual transport through shared inbound ports).
 * Because it is a record, two queries built by two different transports
 * from equivalent payloads are {@code equals} -- which is precisely what
 * {@code TransportQueryEquivalenceTest} asserts.
 *
 * <p>Fields carry the caller's values <em>raw</em>, as received on the
 * wire. Validation (the synthetic-tenant-id rule, blank subject,
 * token verification and authorization) happens once, in the application
 * layer, never in a transport adapter -- so both transports cannot drift
 * into accepting different inputs. The record deliberately holds only JDK
 * types: adapters depend on {@code port.in} and must never need a
 * {@code domain} or {@code rbac} type to build a query.
 *
 * @param tenantId    the tenant whose role assignments are being read
 * @param subjectId   the subject WHOSE permissions are being read; distinct
 *                     from the caller (see {@code bearerToken}) -- this
 *                     endpoint answers "what does subject X hold in tenant
 *                     Y", which an admin console asks about a subject other
 *                     than itself
 * @param bearerToken the caller's raw access token, exactly as it arrived
 *                     on the wire (the REST adapter strips only the
 *                     {@code "Bearer "} scheme prefix; the gRPC adapter
 *                     reads the {@code authorization} metadata entry the
 *                     same way). Empty (never null) when the caller sent no
 *                     token -- ADR 0016 Decision 4: a gateway-supplied
 *                     identity header is never a substitute, and no
 *                     adapter reads one. The application layer verifies
 *                     this token itself; it is never trusted un-verified,
 *                     and it must never be logged.
 */
public record GetTenantPermissionsQuery(String tenantId, String subjectId, String bearerToken) {

    public GetTenantPermissionsQuery {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(bearerToken, "bearerToken");
    }
}
