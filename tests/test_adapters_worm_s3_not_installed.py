"""Proves adapters.worm_s3 lazy-imports boto3: importing the module is
always safe, and constructing an S3WormStore without an injected client
when boto3 cannot be imported raises AdapterNotInstalledError, never a
bare ImportError. Uses sys.modules stubbing so this is meaningful
whether or not boto3 actually happens to be installed in the test
environment (it usually is not, since it's an optional extra -- see
requirements-adapters-s3.txt)."""

import sys
import unittest

from adapters.worm_s3.store import AdapterNotInstalledError, S3WormStore


class Boto3NotInstalledTests(unittest.TestCase):
    def test_module_import_never_requires_boto3(self):
        # Reaching this line at all is the test: the module-level import
        # of adapters.worm_s3.store above must not have required boto3.
        self.assertTrue(hasattr(S3WormStore, "put"))

    def test_missing_boto3_raises_adapter_not_installed_error(self):
        sentinel_present = "boto3" in sys.modules
        previous = sys.modules.get("boto3")
        sys.modules["boto3"] = None  # forces `import boto3` to raise ImportError
        try:
            with self.assertRaises(AdapterNotInstalledError):
                S3WormStore(bucket="synthetic-test-bucket")
        finally:
            if sentinel_present:
                sys.modules["boto3"] = previous
            else:
                del sys.modules["boto3"]


if __name__ == "__main__":
    unittest.main()
