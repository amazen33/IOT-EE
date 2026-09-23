package org.springframework.web.bind.annotation;

/**
 * Stands in for Spring's real {@code @RestController} -- test-fixture
 * only, declared under the real package name (and placed at the
 * matching directory relative to this module's src/test/java, NOT
 * nested under any fixtures package -- a package declaration must match
 * its directory relative to the source root) so ArchUnit's
 * resideInAnyPackage match proves the rule fires without needing the
 * real spring-web dependency on this module's test classpath. Never
 * referenced from real {@code core} source.
 */
public @interface FakeRestController {
}
