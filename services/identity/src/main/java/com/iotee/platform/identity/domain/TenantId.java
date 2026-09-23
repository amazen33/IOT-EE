package com.iotee.platform.identity.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated tenant identifier.
 *
 * <p>Enforces the platform-wide synthetic-data discipline (the
 * development contract's "synthetic inputs only, never raw PII/production
 * data" rule) at the type level for this walking skeleton: every
 * {@code TenantId} constructed here MUST carry the {@value #SYNTHETIC_PREFIX}
 * prefix. This is deliberately stricter than a real, later
 * {@code TenantId} will be (a real one accepts real tenant identifiers) --
 * it exists so that nothing in this bootstrap-scope module can
 * accidentally be pointed at a real tenant ID before real tenancy exists
 * (ADR 0012 non-goals: no real multi-tenancy yet).
 */
public final class TenantId {

    public static final String SYNTHETIC_PREFIX = "synthetic-tenant-";

    private static final Pattern VALID_FORMAT =
            Pattern.compile("^" + Pattern.quote(SYNTHETIC_PREFIX) + "[a-z0-9-]{1,64}$");

    private final String value;

    private TenantId(String value) {
        this.value = value;
    }

    /**
     * Validates and wraps {@code candidate}.
     *
     * @throws TenantIdValidationException if {@code candidate} is null,
     *     blank, missing the {@value #SYNTHETIC_PREFIX} prefix, or
     *     contains characters outside {@code [a-z0-9-]} after the prefix.
     *     A narrow subtype of {@link IllegalArgumentException} (see its
     *     own Javadoc) so callers can catch exactly this validation
     *     failure -- {@code services.identity.web.DomainExceptionAdvice}
     *     is what maps it to an HTTP 400 response.
     */
    public static TenantId of(String candidate) {
        if (candidate == null || !VALID_FORMAT.matcher(candidate).matches()) {
            throw new TenantIdValidationException(
                    "tenant_id must match " + VALID_FORMAT.pattern() + " but was: " + candidate);
        }
        return new TenantId(candidate);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TenantId tenantId && value.equals(tenantId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
