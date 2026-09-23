package org.jooq;

/**
 * Stands in for jOOQ's real {@code org.jooq.DSLContext} -- test-fixture
 * only, declared under the real package name. Never referenced from
 * real {@code services.identity} source.
 */
public interface FakeDslContext {
    void execute(String sql);
}
