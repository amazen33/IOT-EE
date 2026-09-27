package com.iotee.platform.identity.adapter.in.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Reads the caller's bearer token from the {@code authorization} gRPC
 * metadata entry into a {@link Context} value, so
 * {@link TenantPermissionsGrpcService} can read it without either class
 * depending on the other (ADR 0017 Decisions 2-3: both driving adapters
 * translate wire format into {@code port.in} and nothing else).
 *
 * <p>The gRPC twin of {@code adapter.in.rest.TenantPermissionsController}'s
 * {@code Authorization} header handling: strips only the {@code "Bearer "}
 * scheme prefix, reads nothing else, and in particular never reads any
 * gateway-supplied identity metadata entry (ADR 0016 Decision 4).
 * Authentication itself happens once, in the application layer.
 *
 * <p>Registered on {@link GrpcServerRunner}'s {@code Server} via
 * {@code ServerBuilder.intercept}, so it runs for every RPC this service
 * exposes -- today, only {@code TenantPermissionsService.GetTenantPermissions},
 * but a future second RPC on the same server inherits it automatically
 * rather than needing its own copy.
 */
public final class BearerTokenServerInterceptor implements ServerInterceptor {

    /** gRPC metadata keys must be lowercase; this is the standard header name, lowercased. */
    private static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    /** The caller's raw bearer token for this RPC; empty (never null) when absent or non-Bearer. */
    static final Context.Key<String> BEARER_TOKEN_CONTEXT_KEY = Context.key("iotee-identity-bearer-token");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String bearerToken = bearerToken(headers.get(AUTHORIZATION_METADATA_KEY));
        Context context = Context.current().withValue(BEARER_TOKEN_CONTEXT_KEY, bearerToken);
        return Contexts.interceptCall(context, call, headers, next);
    }

    /** Strips the {@code "Bearer "} scheme prefix; absent or non-Bearer becomes empty, never null. */
    private static String bearerToken(String authorizationMetadataValue) {
        if (authorizationMetadataValue == null) {
            return "";
        }
        if (authorizationMetadataValue.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorizationMetadataValue.substring(BEARER_PREFIX.length()).trim();
        }
        return "";
    }
}
