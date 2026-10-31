#!/usr/bin/env python3
"""Archive or restore authenticated, content-addressed bootstrap inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
RELEASE = "https://github.com/fitchmultz/termux-app/releases/download/fold-bootstrap-inputs"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def expected_members(lock_path, profile_path):
    lock = json.loads(lock_path.read_text())
    profile = json.loads(profile_path.read_text())
    lock_hash = sha(lock_path)
    release = lock_path.with_suffix(".InRelease")
    require(sha(profile_path) == lock["profile_sha256"], "Snapshot profile differs from lock")
    require(sha(release) == lock["provenance"]["inrelease_sha256"], "Snapshot InRelease differs from lock")
    inputs = set(profile["sources"].values()) | {record["SHA256"] for record in lock["packages"]}
    require(all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in inputs), "Invalid input SHA-256")
    members = {"inputs/" + digest: digest for digest in inputs}
    members.update({
        "metadata/bootstrap.lock.json": lock_hash,
        "metadata/profile.json": lock["profile_sha256"],
        "metadata/InRelease": lock["provenance"]["inrelease_sha256"],
        "metadata/Packages": lock["provenance"]["index_sha256"],
    })
    return lock, lock_hash, members


def verify_archive(archive_path, expected, stage=None):
    with tarfile.open(archive_path, "r:") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        require(set(names) == set(expected), "Snapshot members differ from lock")
        for member in members:
            digest = hashlib.sha256()
            destination = stage / member.name.removeprefix("inputs/") if stage and member.name.startswith("inputs/") else None
            with archive.extractfile(member) as source:
                if destination:
                    with destination.open("wb") as target:
                        for chunk in iter(lambda: source.read(1024 * 1024), b""):
                            digest.update(chunk)
                            target.write(chunk)
                else:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(chunk)
            require(digest.hexdigest() == expected[member.name], f"Snapshot member checksum mismatch: {member.name}")


def restore(lock_path, profile_path, cache, archives):
    lock_hash = sha(lock_path)
    recorded = json.loads((HERE / "snapshots.json").read_text()).get(lock_hash)
    if recorded is None:
        return False
    _, _, expected = expected_members(lock_path, profile_path)
    require(re.fullmatch(r"[0-9a-f]{64}", recorded), "Invalid snapshot SHA-256")
    archives.mkdir(parents=True, exist_ok=True)
    archive = archives / f"bootstrap-inputs-{recorded}.tar"
    if not archive.exists() and not archive.is_symlink():
        with tempfile.TemporaryDirectory(prefix="snapshot-download-", dir=archives) as directory:
            temporary = Path(directory) / "archive.tar"
            subprocess.run(["curl", "--disable", "--fail", "--location", "--silent", "--show-error",
                            "--proto", "=https", "--proto-redir", "=https", "--output", str(temporary),
                            f"{RELEASE}/{archive.name}"], check=True)
            require(sha(temporary) == recorded, "Downloaded snapshot checksum mismatch")
            os.replace(temporary, archive)
    require(archive.is_file() and sha(archive) == recorded,
            "Snapshot archive cache checksum mismatch; remove it explicitly")

    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="snapshot-inputs-", dir=cache) as directory:
        stage = Path(directory)
        verify_archive(archive, expected, stage)
        inputs = [name.removeprefix("inputs/") for name in expected if name.startswith("inputs/")]
        for digest in inputs:
            target = cache / digest
            if target.exists() or target.is_symlink():
                require(target.is_file() and sha(target) == digest,
                        f"Checksum mismatch in cache: {target}; remove it explicitly")
        for digest in inputs:
            target = cache / digest
            if not target.exists():
                os.replace(stage / digest, target)
    print(f"Restored {len(inputs)} verified bootstrap inputs from snapshot {recorded}")
    return True


def create(lock_path, profile_path, metadata, cache, output):
    lock, _, expected = expected_members(lock_path, profile_path)
    files = {"metadata/bootstrap.lock.json": lock_path, "metadata/profile.json": profile_path,
             "metadata/InRelease": metadata / "InRelease", "metadata/Packages": metadata / "Packages"}
    files.update({name: cache / digest for name, digest in expected.items() if name.startswith("inputs/")})
    for name, path in files.items():
        require(path.is_file() and sha(path) == expected[name],
                f"Snapshot input missing or changed: {name}")
    for record in lock["packages"]:
        require((cache / record["SHA256"]).stat().st_size == int(record["Size"]),
                f"Snapshot package size mismatch: {record['Package']}")
    with tempfile.TemporaryDirectory(prefix="snapshot-authenticate-") as directory:
        regenerated = Path(directory) / "bootstrap.lock.json"
        subprocess.run([sys.executable, str(profile_path.parent / "bootstrap.py"), "refresh",
                        "--metadata", str(metadata), "--cache", str(cache), "--lock", str(regenerated)], check=True)
        require(regenerated.read_bytes() == lock_path.read_bytes() and
                regenerated.with_suffix(".InRelease").read_bytes() == lock_path.with_suffix(".InRelease").read_bytes(),
                "Signed metadata does not reproduce the lock")

    stream = output.open("xb")
    try:
        with stream, tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name, path in sorted(files.items()):
                member = tarfile.TarInfo(name)
                member.size = path.stat().st_size
                member.mode, member.uid, member.gid, member.mtime = 0o644, 0, 0, 0
                with path.open("rb") as source:
                    archive.addfile(member, source)
        verify_archive(output, expected)
    except Exception:
        output.unlink(missing_ok=True)
        raise
    print(f"Snapshot SHA-256: {sha(output)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("create", "restore"):
        child = sub.add_parser(command)
        child.add_argument("--lock", type=Path, default=HERE / "bootstrap.lock.json")
        child.add_argument("--profile", type=Path, default=HERE / "profile.json")
        child.add_argument("--cache", type=Path, required=True)
        if command == "create":
            child.add_argument("--metadata", type=Path, required=True)
            child.add_argument("--output", type=Path, required=True)
        else:
            child.add_argument("--archives", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "create":
        create(args.lock, args.profile, args.metadata, args.cache, args.output)
    elif not restore(args.lock, args.profile, args.cache, args.archives):
        print("No archived snapshot for this lock; the original download path remains available")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError) as error:
        sys.exit(f"snapshot: {error}")
