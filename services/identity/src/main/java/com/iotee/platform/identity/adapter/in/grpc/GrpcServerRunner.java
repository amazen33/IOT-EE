package com.iotee.platform.identity.adapter.in.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns this service's gRPC {@link Server} (ADR 0017 Decision 3).
 *
 * <p>Plain grpc-java, no Spring: {@code config.GrpcServerLifecycle} ties
 * {@link #start()} and {@link #stop()} to the Spring context. Keeping
 * every {@code io.grpc} type inside {@code adapter.in.grpc} is what ADR
 * 0017 Decision 4's per-adapter ban requires; the composition root only
 * sees this class.
 *
 * <p>Registers {@link BearerTokenServerInterceptor} ahead of every service
 * on this server, so {@code TenantPermissionsGrpcService} (and any future
 * RPC added to this same server) can read the caller's bearer token from
 * gRPC {@code Context} without extracting it from {@code Metadata} itself.
 *
 * <p>Port {@code 0} binds an ephemeral port (tests use it); read the bound
 * port back with {@link #getPort()}. The transport is whatever grpc-java
 * finds on the runtime classpath ({@code grpc-netty-shaded}, runtime
 * scope in this module's pom).
 */
public final class GrpcServerRunner {

    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final int configuredPort;
    private final TenantPermissionsGrpcService tenantPermissionsService;

    private Server server;

    public GrpcServerRunner(int configuredPort, TenantPermissionsGrpcService tenantPermissionsService) {
        if (configuredPort < 0 || configuredPort > 65535) {
            throw new IllegalArgumentException("gRPC port out of range: " + configuredPort);
        }
        this.configuredPort = configuredPort;
        this.tenantPermissionsService = Objects.requireNonNull(tenantPermissionsService, "tenantPermissionsService");
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = ServerBuilder.forPort(configuredPort)
                    .addService(tenantPermissionsService)
                    .intercept(new BearerTokenServerInterceptor())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start gRPC server on port " + configuredPort, e);
        }
    }

    public synchronized void stop() {
        Server running = server;
        if (running == null) {
            return;
        }
        server = null;
        running.shutdown();
        try {
            if (!running.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                running.shutdownNow();
            }
        } catch (InterruptedException e) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** The bound port (resolves an ephemeral {@code 0}); {@code -1} when not running. */
    public synchronized int getPort() {
        return server == null ? -1 : server.getPort();
    }
}
