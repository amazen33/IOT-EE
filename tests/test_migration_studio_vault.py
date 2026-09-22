import unittest

from migration_studio.vault import (
    CAPABILITY_ROTATE,
    CAPABILITY_WRITE,
    InMemoryVaultProvider,
    SecretReference,
    VaultProvider,
    is_secret_like_key,
)


class WriteOnlyProvider(VaultProvider):
    name = "write-only-test-provider"
    capabilities = frozenset({CAPABILITY_WRITE})

    def __init__(self):
        super().__init__()
        self.calls = []

    def _write(self, raw_value, *, scope):
        self.calls.append((raw_value, scope))
        return "internal-1"


class NoCapabilityProvider(VaultProvider):
    name = "no-capability-provider"
    capabilities = frozenset()

    def _write(self, raw_value, *, scope):  # pragma: no cover - never reached
        return "unused"


class BadProvider(VaultProvider):
    name = "bad-provider"
    capabilities = frozenset({"delete-everything"})

    def _write(self, raw_value, *, scope):  # pragma: no cover
        return "unused"


class VaultTests(unittest.TestCase):
    def test_store_secret_returns_reference_not_value(self):
        provider = InMemoryVaultProvider()
        reference = provider.store_secret("super-secret-value", scope="tenant:acme")
        self.assertIsInstance(reference, SecretReference)
        self.assertNotIn("super-secret-value", repr(reference))
        self.assertNotIn("super-secret-value", str(reference.__dict__))
        self.assertEqual(provider.known_internal_id_count(), 1)

    def test_reference_ids_are_unique_per_store(self):
        provider = InMemoryVaultProvider()
        first = provider.store_secret("value-a", scope="tenant:acme")
        second = provider.store_secret("value-b", scope="tenant:acme")
        self.assertNotEqual(first.reference_id, second.reference_id)

    def test_write_requires_declared_capability(self):
        provider = NoCapabilityProvider()
        with self.assertRaises(PermissionError):
            provider.store_secret("value", scope="tenant:acme")

    def test_unknown_capability_rejected_at_construction(self):
        with self.assertRaises(ValueError):
            BadProvider()

    def test_rejects_empty_value_or_scope(self):
        provider = InMemoryVaultProvider()
        with self.assertRaises(ValueError):
            provider.store_secret("", scope="tenant:acme")
        with self.assertRaises(ValueError):
            provider.store_secret("value", scope="")
        with self.assertRaises(ValueError):
            provider.store_secret(None, scope="tenant:acme")  # type: ignore[arg-type]

    def test_provider_delegate_receives_raw_value_but_reference_does_not(self):
        provider = WriteOnlyProvider()
        reference = provider.store_secret("the-raw-value", scope="tenant:acme")
        self.assertEqual(provider.calls, [("the-raw-value", "tenant:acme")])
        self.assertNotIn("the-raw-value", repr(reference))

    def test_reference_rejects_malformed_ids(self):
        with self.assertRaises(ValueError):
            SecretReference(reference_id="has space", provider_name="p", scope="s", created_at="2026-01-01T00:00:00Z")
        with self.assertRaises(ValueError):
            SecretReference(reference_id="ok-id", provider_name="", scope="s", created_at="2026-01-01T00:00:00Z")

    def test_is_secret_like_key(self):
        for key in ("password", "Password", "api_key", "apiKey".lower(), "device_token", "client_secret", "credential"):
            with self.subTest(key=key):
                self.assertTrue(is_secret_like_key(key))
        for key in ("tenant_id", "device_id", "level_percent", "hostname"):
            with self.subTest(key=key):
                self.assertFalse(is_secret_like_key(key))


if __name__ == "__main__":
    unittest.main()
