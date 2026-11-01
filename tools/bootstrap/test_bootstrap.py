"""Runner/security contracts; the real two-build payload gate lives in Fold checks."""
import argparse
import copy
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
import zipfile

import bootstrap as b


class BootstrapRunnerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path.cwd() / "build/bootstrap-tests"
        cls.work.mkdir(parents=True, exist_ok=True)
        cls.downloads = tempfile.TemporaryDirectory(dir=cls.work)
        cls.sources = b.source_files(Path(cls.downloads.name))
        cls.lock = b.load_lock(b.HERE / "bootstrap.lock.json")

    @classmethod
    def tearDownClass(cls):
        cls.downloads.cleanup()

    def test_missing_first_alternative_version_and_architecture_fail_closed(self):
        cases = [
            ("rsync", "Depends", "missing-package | openssh", "Missing dependency/package: missing-package"),
            ("curl", "Depends", "libcurl (>= 9999)", "Unsatisfied dependency: curl"),
            ("git", "Depends", "libexpat [arm64]", "Unsupported dependency"),
            ("git", "Pre-Depends", "bash", "Unsupported Pre-Depends"),
            ("git", "Architecture", "x86_64", "Wrong architecture"),
        ]
        for name, field, value, message in cases:
            with self.subTest(name=name, field=field, value=value):
                records = copy.deepcopy(self.lock["packages"])
                next(r for r in records if r["Package"] == name)[field] = value
                with self.assertRaisesRegex(ValueError, message):
                    b.closure(records)
        records = [r for r in self.lock["packages"] if r["Package"] != "libexpat"]
        with self.assertRaisesRegex(ValueError, "Missing dependency/package: libexpat"):
            b.closure(records)

    def test_refreshed_lsof_requires_backport_review_before_build(self):
        with tempfile.TemporaryDirectory(dir=self.work) as directory:
            work = Path(directory)
            changed = copy.deepcopy(self.lock)
            next(r for r in changed["packages"] if r["Package"] == "lsof")["Version"] = "4.99.8"
            lock = work / "bootstrap.lock.json"
            lock.write_text(json.dumps(changed))
            lock.with_suffix(".InRelease").write_bytes((b.HERE / "bootstrap.lock.InRelease").read_bytes())
            output = work / "bootstrap.zip"
            output.write_bytes(b"previous verified artifact")
            result = subprocess.run(["python3", str(b.HERE / "bootstrap.py"), "build", "--offline",
                                     "--lock", str(lock), "--cache", str(work / "cache"),
                                     "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Review the lsof backport against the refreshed official package", result.stderr)
            self.assertEqual(output.read_bytes(), b"previous verified artifact")

    def test_bad_cached_source_or_package_and_missing_offline_input_preserve_output(self):
        for corrupt in (None, "scripts/generate-bootstraps.sh", "apt"):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory(dir=self.work) as directory:
                work = Path(directory)
                cache = work / "cache"
                cache.mkdir()
                if corrupt:
                    for name, source in self.sources.items():
                        (cache / b.PROFILE["sources"][name]).write_bytes(source.read_bytes())
                    digest = (b.PROFILE["sources"][corrupt] if corrupt != "apt" else
                              next(r for r in self.lock["packages"] if r["Package"] == "apt")["SHA256"])
                    (cache / digest).write_bytes(b"untrusted bytes")
                output = work / "bootstrap.zip"
                output.write_bytes(b"previous verified artifact")
                result = subprocess.run(["python3", str(b.HERE / "bootstrap.py"), "build", "--offline",
                                         "--cache", str(cache), "--output", str(output)], capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Checksum mismatch in cache" if corrupt else "Missing offline input", result.stderr)
                self.assertEqual(output.read_bytes(), b"previous verified artifact")
                self.assertFalse(list(work.glob("generate-*")))

    def test_repository_signature_and_index_hash_are_required_before_lock_publication(self):
        original = (b.HERE / "bootstrap.lock.InRelease").read_text()
        for corrupt_release in (True, False):
            with self.subTest(corrupt_release=corrupt_release), tempfile.TemporaryDirectory(dir=self.work) as directory:
                work = Path(directory)
                release = original.replace("Origin: termux-main", "Origin: forged-main") if corrupt_release else original
                (work / "InRelease").write_text(release)
                (work / "Packages").write_text("Package: injected\nVersion: 1\n")
                lock = work / "lock.json"
                lock.write_text("previous lock")
                args = argparse.Namespace(metadata=work, cache=Path(self.downloads.name), lock=lock)
                if corrupt_release:
                    with self.assertRaises(subprocess.CalledProcessError) as error:
                        b.refresh(args)
                    self.assertIn("[GNUPG:] BADSIG", error.exception.stdout)
                else:
                    with self.assertRaisesRegex(ValueError, "Unauthenticated Packages index"):
                        b.refresh(args)
                self.assertEqual(lock.read_text(), "previous lock")

    def test_canonical_archive_normalizes_order_time_and_modes_but_preserves_execute(self):
        with tempfile.TemporaryDirectory(dir=self.work) as directory:
            work = Path(directory)
            for number, names in enumerate((("bin/tool", "bin/plain", "SYMLINKS.txt"),
                                             ("SYMLINKS.txt", "bin/plain", "bin/tool"))):
                with zipfile.ZipFile(work / f"input{number}.zip", "w") as archive:
                    for name in names:
                        info = zipfile.ZipInfo(name, (2020 + number, 2, 3, 4, 5, 6))
                        info.external_attr = ((0o100600 if name == "bin/plain" else 0o100700) | (0o040 if number else 0)) << 16
                        links = "b←./b\na←./a\n" if number == 0 else "a←./a\nb←./b\n"
                        data = links.encode() if name == "SYMLINKS.txt" else b"payload"
                        archive.writestr(info, data)
                b.canonicalize(work / f"input{number}.zip", work / f"output{number}.zip")
            self.assertEqual((work / "output0.zip").read_bytes(), (work / "output1.zip").read_bytes())
            with zipfile.ZipFile(work / "output0.zip") as archive:
                self.assertEqual(archive.read("bin/tool"), b"payload")
                self.assertEqual(archive.read("SYMLINKS.txt").decode(), "a←./a\nb←./b\n")
                self.assertEqual(archive.read("EXECUTABLES.txt").decode(), "bin/tool\n")
                self.assertEqual((archive.getinfo("bin/tool").external_attr >> 16) & 0o777, 0o700)
                self.assertEqual((archive.getinfo("bin/plain").external_attr >> 16) & 0o777, 0o600)

    def test_control_symlinks_cannot_read_host_files(self):
        with tempfile.TemporaryDirectory(dir=self.work) as directory:
            work = Path(directory)
            (work / "debian-binary").write_text("2.0\n")
            with tarfile.open(work / "control.tar.gz", "w:gz") as archive:
                data = b"Package: fixture\nVersion: 1\nArchitecture: all\n"
                member = tarfile.TarInfo("control")
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
                member = tarfile.TarInfo("postinst")
                member.type = tarfile.SYMTYPE
                member.linkname = str(work / "host-data")
                archive.addfile(member)
            (work / "host-data").write_text("not package data")
            with tarfile.open(work / "data.tar.gz", "w:gz"):
                pass
            subprocess.run(["ar", "rc", "fixture.deb", "debian-binary", "control.tar.gz", "data.tar.gz"],
                           cwd=work, check=True)
            with self.assertRaisesRegex(ValueError, "Unsafe control archive entry"):
                b.check_debs([{"Package": "fixture", "Version": "1", "Architecture": "all"}], [work / "fixture.deb"])


if __name__ == "__main__":
    unittest.main()
