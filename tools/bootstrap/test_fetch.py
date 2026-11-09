"""Fetch transport regressions; all subprocesses are mocked, never live."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import bootstrap as b


class FetchTransportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.cache = Path(self.directory.name) / "cache"
        self.payload = b"pinned lsof package fixture\n"
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.url = b.LSOF_BACKPORT["url"]
        self.filename = Path(b.LSOF_BACKPORT["filename"]).name
        # Use a tiny fixture pin, not a downloaded package; SHA-256 validation stays real.
        self.addCleanup(mock.patch.stopall)
        mock.patch.dict(b.LSOF_BACKPORT, {"sha256": self.digest}).start()
        # bootstrap and snapshot share subprocess: any unhandled request fails this test.
        self.run = mock.patch.object(b.subprocess, "run", side_effect=AssertionError("Unexpected curl invocation")).start()
        self.recover = mock.Mock(side_effect=AssertionError("Unexpected snapshot recovery"))

    def metadata(self):
        return {"assets": [
            {"name": "unrelated.deb", "id": 1},
            {"name": self.filename, "id": 694379798,
             "url": "https://untrusted.invalid/asset", "browser_download_url": "http://untrusted.invalid/asset"},
        ]}

    def curl(self, *, browser=None, metadata=None, asset=None, url=None):
        browser = self.payload if browser is None else browser
        metadata = self.metadata() if metadata is None else metadata
        asset = self.payload if asset is None else asset
        url = self.url if url is None else url

        def run(command, **kwargs):
            self.assertEqual(command[:2], ["curl", "--disable"])
            for option in ("--fail", "--location", "--silent", "--show-error"):
                self.assertIn(option, command)
            for option in ("--proto", "--proto-redir"):
                self.assertEqual(command[command.index(option) + 1], "=https")
            self.assertTrue(kwargs["check"])
            self.assertNotIn("env", kwargs)
            self.assertFalse(any("authorization" in part.lower() for part in command))
            request = command[-1]
            if request == url:
                response = browser
            elif request == b.snapshot.RELEASE_API:
                self.assertIn("Accept: application/vnd.github+json", command)
                self.assertTrue(kwargs["capture_output"])
                self.assertTrue(kwargs["text"])
                if isinstance(metadata, Exception):
                    raise metadata
                # A string represents a raw, malformed JSON response.
                return subprocess.CompletedProcess(command, 0, stdout=(
                    metadata if isinstance(metadata, str) else json.dumps(metadata)))
            elif request == f"{b.snapshot.ASSETS_API}/694379798":
                self.assertIn("Accept: application/octet-stream", command)
                response = asset
            else:
                self.fail(f"Unexpected download URL: {request}")
            self.assertFalse((self.cache / self.digest).exists())
            output = Path(command[command.index("--output") + 1])
            output.write_bytes(b"partial download" if isinstance(response, Exception) else response)
            if isinstance(response, Exception):
                raise response
            return subprocess.CompletedProcess(command, 0)

        self.run.side_effect = run

    def fetch(self):
        return b.fetch(self.url, self.digest, self.cache, recover=self.recover)

    def assert_unpublished(self):
        self.assertFalse((self.cache / self.digest).exists())
        self.assertFalse(list(self.cache.glob("download-*")))
        self.recover.assert_not_called()

    def test_browser_success_keeps_original_transport(self):
        self.curl()
        self.assertEqual(self.fetch().read_bytes(), self.payload)
        self.assertEqual(self.run.call_count, 1)
        self.recover.assert_not_called()

    def test_exact_lsof_browser_failure_uses_api_once_and_overwrites_partial(self):
        self.curl(browser=subprocess.CalledProcessError(22, ["curl"], stderr="HTTP 504"))
        self.assertEqual(self.fetch().read_bytes(), self.payload)
        self.assertEqual([call.args[0][-1] for call in self.run.call_args_list], [
            self.url, b.snapshot.RELEASE_API, f"{b.snapshot.ASSETS_API}/694379798"])
        self.recover.assert_not_called()
        self.assertFalse(list(self.cache.glob("download-*")))

    def test_only_exact_lsof_url_and_pin_are_api_eligible(self):
        other_digest = hashlib.sha256(b"different pin").hexdigest()
        for url, digest in ((self.url + "?download=1", self.digest),
                            (self.url + "/", self.digest),
                            (self.url.replace("github.com", "untrusted.invalid"), self.digest),
                            (self.url.replace(self.filename, "other.deb"), self.digest),
                            (self.url, other_digest)):
            with self.subTest(url=url, digest=digest):
                self.run.reset_mock()
                error = subprocess.CalledProcessError(22, ["curl"])
                self.curl(browser=error, url=url)
                with self.assertRaises(subprocess.CalledProcessError) as raised:
                    b.fetch(url, digest, self.cache)
                self.assertIs(raised.exception, error)
                self.assertEqual(self.run.call_count, 1)
                self.assertFalse((self.cache / digest).exists())
                self.assert_unpublished()

    def test_successful_browser_bad_hash_never_tries_api_or_recovery(self):
        self.curl(browser=b"untrusted package")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch:"):
            self.fetch()
        self.assertEqual(self.run.call_count, 1)
        self.assert_unpublished()

    def test_api_bad_hash_never_retries_or_publishes(self):
        self.curl(browser=subprocess.CalledProcessError(22, ["curl"]), asset=b"untrusted package")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch:"):
            self.fetch()
        self.assertEqual(self.run.call_count, 3)
        self.assert_unpublished()

    def test_api_metadata_requires_unique_basename_and_positive_integer_id(self):
        record = {"name": self.filename, "id": 694379798}
        invalid = ["not JSON", [], {"assets": None}, {"assets": []},
                   {"assets": [{**record, "name": b.LSOF_BACKPORT["filename"]}]},
                   {"assets": [{**record, "name": self.filename.upper()}]},
                   {"assets": [record, record]}]
        invalid.extend({"assets": [{**record, "id": value}]} for value in (None, 0, -1, True, 1.5, "694379798"))
        for metadata in invalid:
            with self.subTest(metadata=metadata):
                self.run.reset_mock()
                self.curl(browser=subprocess.CalledProcessError(22, ["curl"]), metadata=metadata)
                with self.assertRaises(ValueError):
                    self.fetch()
                self.assertEqual(self.run.call_count, 2)
                self.assert_unpublished()

    def test_api_transport_failures_stop_without_recovery_or_publication(self):
        for failing in ("metadata", "asset"):
            with self.subTest(failing=failing):
                self.run.reset_mock()
                error = subprocess.CalledProcessError(28, ["curl"])
                self.curl(browser=subprocess.CalledProcessError(22, ["curl"]), **{failing: error})
                with self.assertRaises(subprocess.CalledProcessError) as raised:
                    self.fetch()
                self.assertIs(raised.exception, error)
                self.assertEqual(self.run.call_count, 2 if failing == "metadata" else 3)
                self.assert_unpublished()

    def test_browser_os_error_does_not_start_api_or_recovery(self):
        self.run.side_effect = OSError("cannot execute curl")
        with self.assertRaises(OSError):
            self.fetch()
        self.assertEqual(self.run.call_count, 1)
        self.assert_unpublished()

    def test_valid_cache_is_used_without_network_even_offline(self):
        self.cache.mkdir()
        destination = self.cache / self.digest
        destination.write_bytes(self.payload)
        self.assertEqual(b.fetch(self.url, self.digest, self.cache, offline=True, recover=self.recover), destination)
        self.run.assert_not_called()
        self.recover.assert_not_called()

    def test_bad_cache_is_not_retried_or_replaced(self):
        self.cache.mkdir()
        destination = self.cache / self.digest
        destination.write_bytes(b"corrupt cached package")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch in cache"):
            self.fetch()
        self.run.assert_not_called()
        self.recover.assert_not_called()
        self.assertEqual(destination.read_bytes(), b"corrupt cached package")

    def test_missing_offline_input_never_starts_network_or_recovery(self):
        with self.assertRaisesRegex(ValueError, "Missing offline input"):
            b.fetch(self.url, self.digest, self.cache, offline=True, recover=self.recover)
        self.run.assert_not_called()
        self.assert_unpublished()
        self.assertFalse(self.cache.exists())

    def test_non_lsof_failure_keeps_existing_recovery_and_checksum(self):
        url = "https://packages.example.invalid/ordinary.deb"
        for payload in (self.payload, b"untrusted recovery"):
            with self.subTest(payload=payload):
                self.run.reset_mock()
                self.curl(browser=subprocess.CalledProcessError(22, ["curl"]), url=url)
                destination = self.cache / self.digest

                def recover():
                    destination.write_bytes(payload)
                    return True

                recovery = mock.Mock(side_effect=recover)
                if payload == self.payload:
                    self.assertEqual(b.fetch(url, self.digest, self.cache, recover=recovery), destination)
                else:
                    with self.assertRaisesRegex(ValueError, "Recovered input checksum mismatch"):
                        b.fetch(url, self.digest, self.cache, recover=recovery)
                self.assertEqual(self.run.call_count, 1)
                recovery.assert_called_once_with()
                self.assertEqual(destination.read_bytes(), payload)
                destination.unlink()

    def test_non_lsof_unsuccessful_recovery_reraises_original_failure(self):
        url = "https://packages.example.invalid/ordinary.deb"
        error = subprocess.CalledProcessError(22, ["curl"])
        self.curl(browser=error, url=url)
        recovery = mock.Mock(return_value=False)
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            b.fetch(url, self.digest, self.cache, recover=recovery)
        self.assertIs(raised.exception, error)
        recovery.assert_called_once_with()
        self.assertEqual(self.run.call_count, 1)
        self.assert_unpublished()

    def test_invalid_digest_or_non_https_input_never_starts_network(self):
        for url, digest, message in ((self.url, "invalid", "Invalid SHA-256"),
                                     (self.url.replace("https://", "http://"), self.digest, "Not an HTTPS input")):
            with self.subTest(url=url, digest=digest):
                with self.assertRaisesRegex(ValueError, message):
                    b.fetch(url, digest, self.cache, recover=self.recover)
                self.run.assert_not_called()
                self.assert_unpublished()


if __name__ == "__main__":
    unittest.main()
