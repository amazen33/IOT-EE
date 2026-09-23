package com.iotee.platform.common.rbac;

/**
 * Stands in for the retired {@code com.iotee.platform.common.rbac}
 * package -- ADR 0013 retired {@code common} as a shared runtime
 * module, and this fixture is declared under that exact retired
 * package name (matching this file's own directory, which is the
 * package's real, non-impersonated location) so ArchUnit's
 * resideInAnyPackage match proves the autonomy tripwire rule fires if
 * the retired shared package is ever reintroduced. Test-fixture only,
 * never a real dependency of {@code services.identity}.
 */
public class FakeRetiredCommonRbacClass {
}
