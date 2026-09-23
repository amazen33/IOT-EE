package com.iotee.platform.identity.architecture.fixtures;

import org.eclipse.persistence.FakeEclipseLinkEntityManager;
import org.hibernate.FakeSession;
import org.jooq.FakeDslContext;

/**
 * Deliberately violates the "services.identity must not depend on a
 * persistence provider" rule asserted in
 * {@code IdentityFrameworkFreedomArchitectureRulesTest} by depending on
 * all three denylisted provider packages at once. Never imported by
 * any real {@code services.identity} class -- test-fixture only.
 */
public class ViolatingPersistenceProviderUsageClass {
    public void useHibernateDirectly(FakeSession session) {
        session.persist(new Object());
    }

    public void useEclipseLinkDirectly(FakeEclipseLinkEntityManager entityManager) {
        entityManager.persist(new Object());
    }

    public void useJooqDirectly(FakeDslContext dslContext) {
        dslContext.execute("select 1");
    }
}
