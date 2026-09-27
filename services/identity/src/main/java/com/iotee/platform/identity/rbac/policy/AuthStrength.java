package com.iotee.platform.identity.rbac.policy;

/**
 * How strongly the subject authenticated, ordered weakest to strongest.
 * Derived by an adapter from the token's {@code amr}/{@code acr} claims;
 * this package only compares levels.
 */
public enum AuthStrength {
    /** Password or other single factor. */
    SINGLE_FACTOR,
    /** A second factor such as a TOTP authenticator app. */
    OTP,
    /** Phishing-resistant: WebAuthn passkey or security key. */
    PHISHING_RESISTANT;

    public boolean atLeast(AuthStrength required) {
        return compareTo(required) >= 0;
    }
}
