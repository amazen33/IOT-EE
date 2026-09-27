/**
 * Framework-free authorization policy for {@code services/identity}: a
 * policy decision point (rule-based permissions plus ABAC attributes,
 * deny by default, explicit deny wins), a grant authority for delegation
 * and privileged environment-admin channel grants, and access-token claim
 * validation.
 *
 * <p>JDK types only (ArchUnit: {@code rbac..} is framework-free and may
 * not depend on ports, the application layer, adapters or config). Per
 * ADR 0013 another service that needs the same evaluator copies it into
 * its own package; there is no shared security JAR.
 *
 * <p>What this package does <b>not</b> do: verify token signatures, talk
 * to an identity provider, issue credentials, open bastion sessions, or
 * enforce anything at an HTTP/gRPC boundary. Those are adapter concerns
 * recorded as blocked in {@code docs/identity-access-traceability.md}.
 */
package com.iotee.platform.identity.rbac.policy;
