package com.iotee.platform.identity.config;

import com.iotee.platform.identity.adapter.in.grpc.GrpcServerRunner;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

/**
 * Ties the gRPC adapter's {@link GrpcServerRunner} to the Spring context
 * lifecycle (ADR 0017 Decision 3). Hand-written rather than a third-party
 * gRPC Spring Boot starter: a few lines, no extra dependency, and the
 * gRPC adapter itself stays free of Spring.
 *
 * <p>Configuration ({@code application.yml}):
 * {@code iotee.identity.grpc.enabled} (default {@code true}) and
 * {@code iotee.identity.grpc.port} (default {@code 9091}; {@code 0} =
 * ephemeral, used by tests).
 */
public class GrpcServerLifecycle implements SmartLifecycle {

    private final boolean enabled;
    private final GrpcServerRunner runner;

    public GrpcServerLifecycle(boolean enabled, GrpcServerRunner runner) {
        this.enabled = enabled;
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public void start() {
        if (enabled) {
            runner.start();
        }
    }

    @Override
    public void stop() {
        runner.stop();
    }

    @Override
    public boolean isRunning() {
        return runner.isRunning();
    }

    /** The bound gRPC port, or {@code -1} when disabled or not yet started. */
    public int getPort() {
        return runner.getPort();
    }
}
