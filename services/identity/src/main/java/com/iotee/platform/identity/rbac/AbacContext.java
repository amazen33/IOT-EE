package com.iotee.platform.identity.rbac;

/**
 * Attribute-based-access-control hook stub (ADR 0012 non-goals: no full
 * RBAC/ABAC yet). This interface exists so {@code services/identity}'s
 * walking skeleton has one consistent seam to call for an attribute
 * check -- even though the only implementation provided here
 * ({@link #alwaysPermit()}) makes no real decision.
 *
 * <p>A real ABAC engine (attribute sources, policy evaluation, decision
 * caching) is out of scope for this bootstrap; implement this interface
 * against one when that work happens, without changing any caller.
 *
 * <p><b>This class belongs to {@code services/identity} alone</b> (ADR
 * 0013 Decision 1/6): a future {@code services/device} or any other
 * service that needs the same seam authors its own copy in its own
 * package, rather than depending on this one. Duplication across
 * services is the accepted cost of microservice autonomy, not an
 * oversight to consolidate back into a shared module.
 */
@FunctionalInterface
public interface AbacContext {

    /**
     * Evaluates whether {@code subjectId} may perform {@code permission}
     * on the resource identified by {@code resourceId}, given whatever
     * attributes a real implementation consults.
     */
    AbacDecision evaluate(String subjectId, Permission permission, String resourceId);

    /**
     * A stub implementation that permits every request. Used only where a
     * caller needs an {@link AbacContext} but this bootstrap has no real
     * attribute policy yet (e.g. services/identity's walking skeleton).
     * Never wire this into anything beyond a walking skeleton or a test.
     */
    static AbacContext alwaysPermit() {
        return (subjectId, permission, resourceId) -> AbacDecision.PERMIT;
    }
}
