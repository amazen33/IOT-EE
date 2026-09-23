package org.springframework.stereotype;

/**
 * Stands in for the real {@code org.springframework.stereotype} package --
 * declared under that exact package name (and placed at the matching
 * directory relative to this module's src/test/java, NOT nested under
 * any fixtures package -- a package declaration must match its
 * directory relative to the source root) so ArchUnit's
 * resideInAnyPackage match fires without needing the real Spring
 * dependency on this module's test classpath. Test-fixture only, never
 * referenced from real {@code services.identity.rbac} source.
 */
public class FakeComponent {
}
