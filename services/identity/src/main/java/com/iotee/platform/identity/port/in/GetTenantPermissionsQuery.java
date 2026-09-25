package com.iotee.platform.identity.port.in;

import java.util.Objects;

/**
 * Inbound query: "which permissions does {@code subjectId} hold in
 * {@code tenantId}?" (ADR 0017 Decision 2, {@code port.in}).
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
 * wire. Validation (the synthetic-tenant-id rule, blank subject) happens
 * once, in the application layer, never in a transport adapter -- so both
 * transports cannot drift into accepting different inputs. The record
 * deliberately holds only JDK types: adapters depend on {@code port.in}
 * and must never need a {@code domain} type to build a query.
 */
public record GetTenantPermissionsQuery(String tenantId, String subjectId) {

    public GetTenantPermissionsQuery {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(subjectId, "subjectId");
    }
}
