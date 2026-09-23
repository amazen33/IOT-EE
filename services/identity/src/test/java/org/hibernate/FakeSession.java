package org.hibernate;

/**
 * Stands in for Hibernate's real {@code org.hibernate.Session} --
 * test-fixture only, declared under the real package name; see
 * {@code FakeKafkaProducerClass}'s Javadoc for why (same
 * package-name-match technique). Never referenced from real
 * {@code services.identity} source.
 */
public interface FakeSession {
    void persist(Object entity);
}
