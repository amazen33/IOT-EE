package com.iotee.platform.identity.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CorrelationIdContextTest {

    @AfterEach
    void clearThreadState() {
        CorrelationIdContext.clear();
    }

    @Test
    void originMintsANonBlankValueEachTime() {
        String first = CorrelationIdContext.origin();
        String second = CorrelationIdContext.origin();
        assertFalse(first.isBlank());
        assertNotEquals(first, second, "origin() must not return a fixed or reused value");
    }

    @Test
    void atATrustBoundaryAnInboundValueIsAlwaysDiscarded() {
        String inbound = "client-supplied-value";
        String resolved = CorrelationIdContext.adoptOrOrigin(inbound, true);
        assertNotEquals(inbound, resolved, "a trust-boundary call must never adopt a caller-supplied correlation id");
    }

    @Test
    void aNonBoundaryCallPropagatesAnExistingInboundValue() {
        String inbound = "upstream-service-value";
        String resolved = CorrelationIdContext.adoptOrOrigin(inbound, false);
        assertEquals(inbound, resolved, "an internal hop must propagate, not replace, an existing correlation id");
    }

    @Test
    void aNonBoundaryCallWithNoInboundValueStillGetsOne() {
        String resolved = CorrelationIdContext.adoptOrOrigin(null, false);
        assertFalse(resolved.isBlank());
    }

    @Test
    void aNonBoundaryCallWithABlankInboundValueMintsAFreshOne() {
        String resolved = CorrelationIdContext.adoptOrOrigin("   ", false);
        assertFalse(resolved.isBlank());
        assertNotEquals("   ", resolved);
    }

    @Test
    void setThenGetRoundTrips() {
        assertNull(CorrelationIdContext.get(), "precondition: nothing bound yet on this thread");
        CorrelationIdContext.set("abc-123");
        assertEquals("abc-123", CorrelationIdContext.get());
    }

    @Test
    void clearRemovesTheBoundValue() {
        CorrelationIdContext.set("abc-123");
        CorrelationIdContext.clear();
        assertNull(CorrelationIdContext.get());
    }

    @Test
    void setRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> CorrelationIdContext.set(""));
        assertThrows(IllegalArgumentException.class, () -> CorrelationIdContext.set("   "));
    }

    @Test
    void setRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> CorrelationIdContext.set(null));
    }

    @Test
    void getIsThreadIsolated() throws InterruptedException {
        CorrelationIdContext.set("main-thread-value");
        String[] otherThreadValue = new String[1];
        Thread other = new Thread(() -> otherThreadValue[0] = CorrelationIdContext.get());
        other.start();
        other.join();
        assertNull(otherThreadValue[0], "a value bound on one thread must not be visible on another");
        assertTrue(CorrelationIdContext.get().equals("main-thread-value"));
    }

    @Test
    void captureSnapshotsTheCurrentThreadsBoundValue() {
        CorrelationIdContext.set("snapshot-me");
        assertEquals("snapshot-me", CorrelationIdContext.capture());
    }

    @Test
    void captureReturnsNullWhenNothingIsBound() {
        assertNull(CorrelationIdContext.capture());
    }

    @Test
    void restorePropagatesACapturedValueAcrossAnAsyncHandoff() throws InterruptedException {
        CorrelationIdContext.set("origin-thread-value");
        String captured = CorrelationIdContext.capture();

        String[] handedOffValue = new String[1];
        Thread continuation = new Thread(() -> {
            CorrelationIdContext.restore(captured);
            handedOffValue[0] = CorrelationIdContext.get();
            CorrelationIdContext.clear();
        });
        continuation.start();
        continuation.join();

        assertEquals("origin-thread-value", handedOffValue[0]);
    }

    @Test
    void restoreOfANullCapturedValueClearsRatherThanThrows() {
        CorrelationIdContext.set("will-be-cleared");
        CorrelationIdContext.restore(null);
        assertNull(CorrelationIdContext.get(), "restore(null) must clear, not throw, unlike set(null)");
    }
}
