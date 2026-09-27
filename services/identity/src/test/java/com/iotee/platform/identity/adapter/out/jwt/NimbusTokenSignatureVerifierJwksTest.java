package com.iotee.platform.identity.adapter.out.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.iotee.platform.identity.rbac.policy.AccessTokenClaims;
import com.iotee.platform.identity.rbac.policy.AccessTokenValidator;
import com.iotee.platform.identity.rbac.policy.AuthStrength;
import com.iotee.platform.identity.rbac.policy.ClientAudience;
import com.iotee.platform.identity.rbac.policy.SubjectTier;
import com.iotee.platform.identity.rbac.policy.TokenRejectedException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises the real Nimbus adapter against an in-process, rotating JWKS endpoint. */
class NimbusTokenSignatureVerifierJwksTest {

    private static final String TIER_CLAIM = "https://iotee.io/tier";
    private static final String TENANT_CLAIM = "https://iotee.io/tenant_id";
    private static final Instant NOW = Instant.parse("2026-09-26T09:00:00Z");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void verifiesSignedClaimsAndRefreshesJwksForANewKeyId() throws Exception {
        RSAKey first = key("key-1");
        AtomicReference<JWKSet> published = serve(new JWKSet(first.toPublicJWK()));
        NimbusTokenSignatureVerifier verifier = verifier();

        AccessTokenClaims claims = verifier.verify(signed(first));
        assertEquals("synthetic-subject-admin", claims.subject());
        assertEquals(SubjectTier.TENANT_ADMIN, claims.tier());
        assertEquals("synthetic-tenant-acme-001", claims.tenantId());
        assertEquals(NOW.minusSeconds(30), claims.authTime());
        AccessTokenValidator admin = new AccessTokenValidator("https://issuer.invalid/", "iotee-admin-console",
                Set.of("iotee-operator-console"), Set.of("iotee-admin-console-client"),
                ClientAudience.ADMIN_CONSOLE, Set.of(SubjectTier.TENANT_ADMIN),
                Duration.ofMinutes(10), Duration.ofSeconds(30), Map.of("otp", AuthStrength.OTP));
        assertEquals(SubjectTier.TENANT_ADMIN, admin.validate(claims, NOW).tier());
        TokenRejectedException expired = assertThrows(TokenRejectedException.class,
                () -> admin.validate(claims, NOW.plusSeconds(400)));
        assertEquals(TokenRejectedException.Reason.EXPIRED, expired.reason());

        RSAKey replacement = key("key-2");
        published.set(new JWKSet(replacement.toPublicJWK()));
        assertEquals("synthetic-subject-admin", verifier.verify(signed(replacement)).subject());
    }

    @Test
    void rejectsUnsignedSubstitutedAlgorithmAndWrongSignature() throws Exception {
        RSAKey trusted = key("trusted");
        serve(new JWKSet(trusted.toPublicJWK()));
        NimbusTokenSignatureVerifier verifier = verifier();

        assertInvalidSignature(verifier, new PlainJWT(claims()).serialize());
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("trusted").build(), claims());
        hmac.sign(new MACSigner(new byte[32]));
        assertInvalidSignature(verifier, hmac.serialize());

        RSAKey attacker = key("attacker");
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("trusted").build(), claims());
        forged.sign(new RSASSASigner(attacker));
        assertInvalidSignature(verifier, forged.serialize());
    }

    private static void assertInvalidSignature(NimbusTokenSignatureVerifier verifier, String token) {
        TokenRejectedException rejected = assertThrows(TokenRejectedException.class, () -> verifier.verify(token));
        assertEquals(TokenRejectedException.Reason.INVALID_SIGNATURE, rejected.reason());
    }

    private NimbusTokenSignatureVerifier verifier() {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        return new NimbusTokenSignatureVerifier(uri, "RS256", Duration.ofSeconds(2), Duration.ofSeconds(2),
                "azp", "auth_time", "amr", TIER_CLAIM,
                Map.of("tenant_admin", SubjectTier.TENANT_ADMIN), TENANT_CLAIM);
    }

    private AtomicReference<JWKSet> serve(JWKSet initial) throws Exception {
        AtomicReference<JWKSet> published = new AtomicReference<>(initial);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = published.get().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return published;
    }

    private static RSAKey key(String kid) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate()).keyID(kid).build();
    }

    private static String signed(RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder().issuer("https://issuer.invalid/")
                .audience("iotee-admin-console").subject("synthetic-subject-admin")
                .issueTime(Date.from(NOW.minusSeconds(60))).expirationTime(Date.from(NOW.plusSeconds(240)))
                .claim("azp", "iotee-admin-console-client").claim("auth_time", NOW.minusSeconds(30).getEpochSecond())
                .claim("amr", List.of("otp")).claim(TIER_CLAIM, "tenant_admin")
                .claim(TENANT_CLAIM, "synthetic-tenant-acme-001").build();
    }
}
