package com.iotee.platform.identity.adapter.in.grpc;

import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsRequest;
import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsResponse;
import com.iotee.platform.contracts.identity.v1.TenantPermissionsServiceGrpc;
import com.iotee.platform.identity.port.in.AuthenticationFailedException;
import com.iotee.platform.identity.port.in.AuthorizationDeniedException;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC driving adapter (ADR 0017 Decisions 2-3, {@code adapter.in.grpc})
 * for {@code TenantPermissionsService.GetTenantPermissions}, generated
 * from {@code contracts/identity/v1/tenant_permissions.proto}.
 *
 * <p>The twin of {@code adapter.in.rest.TenantPermissionsController}: it
 * translates {@link GetTenantPermissionsRequest} into the SAME
 * {@link GetTenantPermissionsQuery} and calls the SAME
 * {@link GetTenantPermissionsUseCase}. Translation only; no business
 * logic, and no reference to the REST adapter (enforced by
 * {@code IdentityHexagonalArchitectureRulesTest}).
 *
 * <p>Deliberately free of Spring: it is a plain grpc-java service,
 * constructed and registered by the composition root in {@code config}.
 * The caller's bearer token is read from
 * {@link BearerTokenServerInterceptor#BEARER_TOKEN_CONTEXT_KEY}, which
 * {@link GrpcServerRunner} registers as a {@code ServerInterceptor} ahead
 * of every RPC on this server -- never from any gateway-supplied identity
 * metadata entry (ADR 0016 Decision 4). Authentication itself happens
 * once, in the application layer.
 *
 * <p>{@link InvalidQueryException} maps to {@code INVALID_ARGUMENT} (HTTP
 * 400's gRPC equivalent), {@link AuthenticationFailedException} to
 * {@code UNAUTHENTICATED} (401's), and {@link AuthorizationDeniedException}
 * to {@code PERMISSION_DENIED} (403's) -- each with a fixed, client-safe
 * description, mirroring {@code adapter.in.rest.RestExceptionAdvice}
 * exactly. Correlation-ID propagation over gRPC metadata (ADR 0015) is not
 * wired yet; it belongs to Track C step C2, which adds all four ADR 0015
 * carriers together.
 */
public final class TenantPermissionsGrpcService
        extends TenantPermissionsServiceGrpc.TenantPermissionsServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(TenantPermissionsGrpcService.class);

    static final String CLIENT_SAFE_DETAIL = "request could not be validated";
    static final String AUTHENTICATION_CLIENT_SAFE_DETAIL = "authentication required";
    static final String AUTHORIZATION_CLIENT_SAFE_DETAIL = "access denied";

    private final GetTenantPermissionsUseCase getTenantPermissions;

    public TenantPermissionsGrpcService(GetTenantPermissionsUseCase getTenantPermissions) {
        this.getTenantPermissions = Objects.requireNonNull(getTenantPermissions, "getTenantPermissions");
    }

    @Override
    public void getTenantPermissions(
            GetTenantPermissionsRequest request, StreamObserver<GetTenantPermissionsResponse> responseObserver) {
        TenantPermissionsView view;
        try {
            view = getTenantPermissions.getTenantPermissions(toQuery(request));
        } catch (InvalidQueryException ex) {
            Throwable detail = ex.getCause() != null ? ex.getCause() : ex;
            LOG.warn("Rejected invalid gRPC request: {}", detail.getMessage());
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(CLIENT_SAFE_DETAIL).asRuntimeException());
            return;
        } catch (AuthenticationFailedException ex) {
            Throwable detail = ex.getCause() != null ? ex.getCause() : ex;
            LOG.warn("Rejected unauthenticated gRPC request: {}", detail.getMessage());
            responseObserver.onError(
                    Status.UNAUTHENTICATED.withDescription(AUTHENTICATION_CLIENT_SAFE_DETAIL).asRuntimeException());
            return;
        } catch (AuthorizationDeniedException ex) {
            LOG.warn("Denied gRPC request: {}", ex.getMessage());
            responseObserver.onError(
                    Status.PERMISSION_DENIED.withDescription(AUTHORIZATION_CLIENT_SAFE_DETAIL).asRuntimeException());
            return;
        }
        responseObserver.onNext(toResponse(view));
        responseObserver.onCompleted();
    }

    /**
     * Wire-to-port translation: the gRPC half of the transport mapping. The
     * bearer token comes from gRPC {@code Context}, not from
     * {@code request} itself -- {@code GetTenantPermissionsRequest} carries
     * no token field; a bearer token belongs in the RPC's metadata, exactly
     * like an HTTP {@code Authorization} header, never in the message body.
     */
    static GetTenantPermissionsQuery toQuery(GetTenantPermissionsRequest request) {
        String bearerToken = BearerTokenServerInterceptor.BEARER_TOKEN_CONTEXT_KEY.get();
        return new GetTenantPermissionsQuery(
                request.getTenantId(), request.getSubjectId(), bearerToken == null ? "" : bearerToken);
    }

    static GetTenantPermissionsResponse toResponse(TenantPermissionsView view) {
        return GetTenantPermissionsResponse.newBuilder()
                .setTenantId(view.tenantId())
                .setSubjectId(view.subjectId())
                .addAllPermissions(view.permissions())
                .build();
    }
}
