package com.iotee.platform.identity.architecture.fixtures.transport;

import io.grpc.Status;

/**
 * Deliberately violates ADR 0017 Decision 4's "{@code io.grpc..} only in
 * {@code adapter.in.grpc}" ({@code IdentityHexagonalArchitectureRulesTest}):
 * it resides in {@code services.identity} outside the gRPC adapter and
 * uses a real grpc-java type. Test-fixture only.
 */
public class ViolatingGrpcUseOutsideGrpcAdapter {
    public Status leakTransportStatus() {
        return Status.INVALID_ARGUMENT;
    }
}
