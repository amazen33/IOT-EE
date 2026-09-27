package com.iotee.platform.identity.rbac.policy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable, versioned set of permission policies and explicit denies.
 * A permission that is not in the set is denied (deny by default).
 */
public final class PolicySet {

    private final int version;
    private final Map<String, PermissionPolicy> policies;
    private final List<DenyRule> denyRules;

    public PolicySet(int version, List<PermissionPolicy> policies, List<DenyRule> denyRules) {
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        this.version = version;
        Map<String, PermissionPolicy> byKey = new HashMap<>();
        for (PermissionPolicy policy : Objects.requireNonNull(policies, "policies")) {
            if (byKey.put(policy.permission(), policy) != null) {
                throw new IllegalArgumentException("duplicate policy for " + policy.permission());
            }
        }
        this.policies = Map.copyOf(byKey);
        this.denyRules = List.copyOf(Objects.requireNonNull(denyRules, "denyRules"));
    }

    public int version() {
        return version;
    }

    public Optional<PermissionPolicy> policyFor(String permission) {
        return Optional.ofNullable(policies.get(permission));
    }

    public List<DenyRule> denyRules() {
        return denyRules;
    }

    /** A new version with one more explicit deny; this set is unchanged. */
    public PolicySet withDenyRule(DenyRule rule) {
        List<DenyRule> rules = new java.util.ArrayList<>(denyRules);
        rules.add(Objects.requireNonNull(rule, "rule"));
        return new PolicySet(version + 1, List.copyOf(policies.values()), rules);
    }
}
