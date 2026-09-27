package com.iotee.platform.identity.architecture.fixtures;

import com.stripe.FakeStripeClient;
import org.keycloak.FakeKeycloakAdminClient;

/**
 * Deliberately violates the "no vendor SDK outside adapters" rule for the
 * identity-provider ({@code org.keycloak}) and payment-provider
 * ({@code com.stripe}) entries of the denylist. Test-fixture only.
 */
public class ViolatingIdpAndPaymentSdkUsageClass {
    public void useVendorSdksDirectly() {
        new FakeKeycloakAdminClient().listRealms();
        new FakeStripeClient().createCheckoutSession();
    }
}
