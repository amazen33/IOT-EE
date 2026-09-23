package com.iotee.platform.identity.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AbacContextTest {

    @Test
    void alwaysPermitPermitsAnyEvaluation() {
        AbacContext stub = AbacContext.alwaysPermit();

        assertEquals(AbacDecision.PERMIT,
                stub.evaluate("synthetic-subject-x", Permission.TENANT_MANAGE, "synthetic-resource-y"));
        assertEquals(AbacDecision.PERMIT,
                stub.evaluate("synthetic-subject-any", Permission.TENANT_READ, "synthetic-resource-any"));
    }
}
