package com.iotee.platform.identity.rbac.policy;

import java.util.Objects;

/**
 * The outcome of a policy evaluation.
 *
 * @param reason        why; {@link DecisionReason#isPermit()} tells permit from deny
 * @param detail        the grant or rule id involved, or the offending permission; may be null
 * @param policyVersion the {@link PolicySet#version()} that decided
 */
public record PolicyDecision(DecisionReason reason, String detail, int policyVersion) {

    public PolicyDecision {
        Objects.requireNonNull(reason, "reason");
    }

    public boolean permitted() {
        return reason.isPermit();
    }

    static PolicyDecision deny(DecisionReason reason, String detail, int version) {
        return new PolicyDecision(reason, detail, version);
    }
}
