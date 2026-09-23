package org.eclipse.persistence;

/**
 * Stands in for an EclipseLink-specific type under
 * {@code org.eclipse.persistence} -- test-fixture only, declared under
 * the real package name. Never referenced from real
 * {@code services.identity} source.
 */
public interface FakeEclipseLinkEntityManager {
    void persist(Object entity);
}
