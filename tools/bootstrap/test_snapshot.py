"""Snapshot transport regressions; every curl invocation is mocked, never live."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import snapshot as s


class SnapshotTransportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.lock = self.root / "bootstrap.lock.json"
        self.profile = self.root / "profile.json"
        self.cache = self.root / "cache"
        self.archives = self.root / "archives"
        self.source = b"pinned generator source\n"
        self.package = b"pinned package bytes\n"
        self.source_hash = hashlib.sha256(self.source).hexdigest()
        self.package_hash = hashlib.sha256(self.package).hexdigest()
        release = b"fixture signed-index envelope\n"
        packages = b"fixture Packages index\n"
        self.profile.write_text(json.dumps({"sources": {"generator": self.source_hash}}))
        self.lock.with_suffix(".InRelease").write_bytes(release)
        self.lock.write_text(json.dumps({
            "profile_sha256": s.sha(self.profile),
            "packages": [{"SHA256": self.package_hash}],
            "provenance": {
                "inrelease_sha256": hashlib.sha256(release).hexdigest(),
                "index_sha256": hashlib.sha256(packages).hexdigest(),
            },
        }))
        self.members = {
            "inputs/" + self.source_hash: self.source,
            "inputs/" + self.package_hash: self.package,
            "metadata/bootstrap.lock.json": self.lock.read_bytes(),
            "metadata/profile.json": self.profile.read_bytes(),
            "metadata/InRelease": release,
            "metadata/Packages": packages,
        }
        self.set_archive(self.members)
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(s, "HERE", self.root).start()
        # An omitted or newly introduced subprocess path must not make a live request.
        self.run = mock.patch.object(s.subprocess, "run", side_effect=AssertionError("Unexpected curl invocation")).start()

    def set_archive(self, members):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            for name, payload in sorted(members.items()):
                member = tarfile.TarInfo(name)
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
        self.payload = stream.getvalue()
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.archive_name = f"bootstrap-inputs-{self.digest}.tar"
        (self.root / "snapshots.json").write_text(json.dumps({s.sha(self.lock): self.digest}))

    def metadata(self):
        return {"assets": [
            {"name": "unrelated.tar", "id": 1},
            {"name": self.archive_name, "id": 694379797,
             "url": "https://untrusted.invalid/asset", "browser_download_url": "http://untrusted.invalid/asset"},
        ]}

    def curl(self, *, browser=None, metadata=None, asset=None):
        browser = self.payload if browser is None else browser
        metadata = self.metadata() if metadata is None else metadata
        asset = self.payload if asset is None else asset

        def run(command, **kwargs):
            self.assertEqual(command[:2], ["curl", "--disable"])
            for option in ("--fail", "--location", "--silent", "--show-error"):
                self.assertIn(option, command)
            for option in ("--proto", "--proto-redir"):
                self.assertEqual(command[command.index(option) + 1], "=https")
            self.assertTrue(kwargs["check"])
            self.assertNotIn("env", kwargs)
            self.assertFalse(any("authorization" in part.lower() for part in command))
            url = command[-1]
            if url == f"{s.RELEASE}/{self.archive_name}":
                response = browser
            elif url == s.RELEASE_API:
                self.assertIn("Accept: application/vnd.github+json", command)
                self.assertTrue(kwargs["capture_output"])
                self.assertTrue(kwargs["text"])
                if isinstance(metadata, Exception):
                    raise metadata
                return subprocess.CompletedProcess(command, 0, stdout=json.dumps(metadata))
            elif url == f"{s.ASSETS_API}/694379797":
                self.assertIn("Accept: application/octet-stream", command)
                response = asset
            else:
                self.fail(f"Unexpected download URL: {url}")
            output = Path(command[command.index("--output") + 1])
            # A failed download may leave a partial file; fallback must overwrite it.
            output.write_bytes(b"partial download" if isinstance(response, Exception) else response)
            if isinstance(response, Exception):
                raise response
            return subprocess.CompletedProcess(command, 0)

        self.run.side_effect = run

    def restore(self):
        return s.restore(self.lock, self.profile, self.cache, self.archives)

    def assert_restored(self):
        self.assertEqual((self.cache / self.source_hash).read_bytes(), self.source)
        self.assertEqual((self.cache / self.package_hash).read_bytes(), self.package)
        self.assertEqual((self.archives / self.archive_name).read_bytes(), self.payload)

    def assert_unpublished(self):
        self.assertFalse((self.archives / self.archive_name).exists())
        self.assertFalse((self.cache / self.source_hash).exists())
        self.assertFalse((self.cache / self.package_hash).exists())
        self.assertFalse(list(self.archives.glob("snapshot-download-*")))

    def test_browser_success_keeps_original_transport(self):
        self.curl()
        self.assertTrue(self.restore())
        self.assertEqual(self.run.call_count, 1)
        self.assert_restored()

    def test_browser_failure_uses_fixed_public_api_and_overwrites_partial(self):
        self.curl(browser=subprocess.CalledProcessError(22, ["curl"], stderr="HTTP 504"))
        self.assertTrue(self.restore())
        self.assertEqual([call.args[0][-1] for call in self.run.call_args_list], [
            f"{s.RELEASE}/{self.archive_name}", s.RELEASE_API, f"{s.ASSETS_API}/694379797"])
        self.assert_restored()

    def test_successful_browser_with_bad_hash_never_tries_api(self):
        self.curl(browser=b"untrusted archive")
        with self.assertRaisesRegex(ValueError, "Downloaded snapshot checksum mismatch"):
            self.restore()
        self.assertEqual(self.run.call_count, 1)
        self.assert_unpublished()

    def test_api_bad_hash_never_retries_or_publishes(self):
        self.curl(browser=subprocess.CalledProcessError(22, ["curl"]), asset=b"untrusted archive")
        with self.assertRaisesRegex(ValueError, "Downloaded snapshot checksum mismatch"):
            self.restore()
        self.assertEqual(self.run.call_count, 3)
        self.assert_unpublished()

    def test_metadata_requires_unique_exact_name_and_positive_integer_id(self):
        record = {"name": self.archive_name, "id": 694379797}
        invalid = [[], {"assets": None}, {"assets": []},
                   {"assets": [{**record, "name": self.archive_name.upper()}]},
                   {"assets": [record, record]}]
        invalid.extend({"assets": [{**record, "id": value}]} for value in (None, 0, -1, True, 1.5, "694379797"))
        for metadata in invalid:
            with self.subTest(metadata=metadata):
                self.run.reset_mock()
                self.curl(browser=subprocess.CalledProcessError(22, ["curl"]), metadata=metadata)
                with self.assertRaises(ValueError):
                    self.restore()
                self.assertEqual(self.run.call_count, 2)
                self.assert_unpublished()

    def test_api_transport_failures_stop_without_publication(self):
        for failing in ("metadata", "asset"):
            with self.subTest(failing=failing):
                self.run.reset_mock()
                self.curl(browser=subprocess.CalledProcessError(22, ["curl"]),
                          **{failing: subprocess.CalledProcessError(28, ["curl"])})
                with self.assertRaises(subprocess.CalledProcessError):
                    self.restore()
                self.assertEqual(self.run.call_count, 2 if failing == "metadata" else 3)
                self.assert_unpublished()

    def test_browser_os_error_does_not_start_api_fallback(self):
        self.run.side_effect = OSError("cannot execute curl")
        with self.assertRaises(OSError):
            self.restore()
        self.assertEqual(self.run.call_count, 1)
        self.assert_unpublished()

    def test_valid_cached_archive_is_verified_without_network(self):
        self.archives.mkdir()
        (self.archives / self.archive_name).write_bytes(self.payload)
        self.assertTrue(self.restore())
        self.run.assert_not_called()
        self.assert_restored()

    def test_bad_cached_archive_is_not_retried_or_replaced(self):
        self.archives.mkdir()
        archive = self.archives / self.archive_name
        archive.write_bytes(b"corrupt cached archive")
        with self.assertRaisesRegex(ValueError, "Snapshot archive cache checksum mismatch"):
            self.restore()
        self.run.assert_not_called()
        self.assertEqual(archive.read_bytes(), b"corrupt cached archive")
        self.assertFalse(self.cache.exists())

    def test_member_verification_still_prevents_input_publication(self):
        changed = dict(self.members)
        changed["inputs/" + self.package_hash] = b"wrong member despite matching outer archive hash"
        self.set_archive(changed)
        self.curl(browser=subprocess.CalledProcessError(22, ["curl"]))
        with self.assertRaisesRegex(ValueError, "Snapshot member checksum mismatch"):
            self.restore()
        self.assertEqual(self.run.call_count, 3)
        self.assertFalse((self.cache / self.source_hash).exists())
        self.assertFalse((self.cache / self.package_hash).exists())
        self.assertFalse(list(self.cache.glob("snapshot-inputs-*")))

    def test_bad_cached_input_is_not_replaced(self):
        self.cache.mkdir()
        target = self.cache / self.package_hash
        target.write_bytes(b"corrupt cached input")
        self.curl(browser=subprocess.CalledProcessError(22, ["curl"]))
        with self.assertRaisesRegex(ValueError, "Checksum mismatch in cache"):
            self.restore()
        self.assertEqual(self.run.call_count, 3)
        self.assertEqual(target.read_bytes(), b"corrupt cached input")
        self.assertFalse((self.cache / self.source_hash).exists())


if __name__ == "__main__":
    unittest.main()
