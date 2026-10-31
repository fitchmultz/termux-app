#!/usr/bin/env python3
"""Lock authenticated Android-7 packages; build offline with the pinned upstream generator."""
import argparse
import hashlib
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import zipfile
import zlib

import snapshot

HERE = Path(__file__).resolve().parent
PROFILE = json.loads((HERE / "profile.json").read_text())
PREFIX = "/data/data/com.termux/files/usr/"
FIELDS = ("Package", "Version", "Architecture", "Filename", "Size", "SHA256",
          "Depends", "Pre-Depends", "Conflicts", "Breaks")
# Public Android NDK libraries available on API 24 (not host/Termux libraries).
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
               "libjnigraphics.so", "libOpenSLES.so", "libEGL.so", "libGLESv1_CM.so",
               "libGLESv2.so", "libGLESv3.so", "libmediandk.so", "libvulkan.so",
               "libcamera2ndk.so", "libstdc++.so"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(data)
            stream.close()
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def fetch(url, digest, cache, offline=False, recover=None):
    require(re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid SHA-256")
    destination = cache / digest
    if destination.exists():
        require(destination.is_file() and sha(destination) == digest,
                f"Checksum mismatch in cache: {destination}; remove it explicitly")
        return destination
    require(not offline, f"Missing offline input: {url} ({digest})")
    require(url.startswith("https://"), f"Not an HTTPS input: {url}")
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="download-", dir=cache) as work:
        temporary = Path(work) / "input"
        # Constrain redirects to HTTPS too; never load the caller's curlrc.
        try:
            subprocess.run(["curl", "--disable", "--fail", "--location", "--silent", "--show-error",
                            "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "30",
                            "--max-time", "300", "--output", str(temporary), url], check=True)
        except subprocess.CalledProcessError:
            if recover is None or not recover():
                raise
            require(destination.is_file() and sha(destination) == digest,
                    f"Recovered input checksum mismatch: {destination}")
            return destination
        require(sha(temporary) == digest, f"Checksum mismatch: {url}")
        os.replace(temporary, destination)
    return destination


def source_files(cache, offline=False, recover=None):
    base = "https://raw.githubusercontent.com/termux/termux-packages/" + PROFILE["upstream_commit"]
    return {name: fetch(f"{base}/{name}", digest, cache, offline, recover)
            for name, digest in PROFILE["sources"].items()}


def paragraphs(text):
    records = []
    for stanza in re.split(r"\n\s*\n", text.strip()):
        record = {}
        key = None
        for line in stanza.splitlines():
            if line.startswith((" ", "\t")):
                require(key is not None, "Invalid metadata continuation")
                record[key] += "\n" + line
            else:
                key, value = line.split(":", 1)
                require(key not in record, f"Duplicate metadata field: {key}")
                record[key] = value.strip()
        records.append(record)
    return records


def dependency(expression):
    # Match the pinned generator: use the FIRST alternative, not a custom solver.
    token = expression.split("|")[0].strip()
    match = re.fullmatch(r"([a-z0-9][a-z0-9+.-]*)(?:\s*\((<<|<=|=|>=|>>)\s*([^()\s]+)\))?", token)
    require(match is not None, f"Unsupported dependency: {expression}")
    return match.groups()


def version_matches(actual, operator, expected):
    result = subprocess.run(["dpkg", "--compare-versions", actual, operator, expected])
    require(result.returncode in (0, 1), "dpkg version comparison failed")
    return result.returncode == 0


def closure(records):
    packages = {}
    for record in records:
        name = record["Package"]
        require(name not in packages, f"Duplicate package: {name}")
        packages[name] = record
    visited = set()

    def visit(name):
        require(name in packages, f"Missing dependency/package: {name}")
        if name in visited:
            return
        visited.add(name)
        require(not re.fullmatch(r"(python.*|nodejs.*|perl|ruby.*|clang.*|gcc.*|rust.*|golang.*|openjdk.*|termux-services|runit)", name),
                f"Runtime/compiler/service manager outside APK baseline: {name}")
        record = packages[name]
        require(record["Architecture"] in ("all", "aarch64"), f"Wrong architecture: {name}")
        # Upstream does not process Pre-Depends: don't silently produce broken bootstraps.
        require(not record.get("Pre-Depends"), f"Unsupported Pre-Depends: {name}")
        for expression in filter(None, record.get("Depends", "").split(",")):
            dep, operator, version = dependency(expression)
            visit(dep)
            require(not operator or version_matches(packages[dep]["Version"], operator, version),
                    f"Unsatisfied dependency: {name} requires {expression}")

    for name in PROFILE["base_packages"] + PROFILE["additional_packages"] + PROFILE["supplemental_packages"]:
        visit(name)
    for name in visited:
        for field in ("Conflicts", "Breaks"):
            for expression in filter(None, packages[name].get(field, "").split(",")):
                other, operator, version = dependency(expression)
                if other in visited and other != name:
                    require(operator and not version_matches(packages[other]["Version"], operator, version),
                            f"{name} {field}: {expression}")
    return [packages[name] for name in sorted(visited)]


def refresh(args):
    sources = source_files(args.cache)
    release = args.metadata / "InRelease"
    index = args.metadata / "Packages"
    with tempfile.TemporaryDirectory(dir=args.cache, prefix="authenticate-") as work:
        work = Path(work)
        result = subprocess.run(["gpgv", "--homedir", str(work), "--status-fd", "1", "--keyring",
                                 str(sources["packages/termux-keyring/termux-autobuilds.gpg"]),
                                 str(release)],
                                check=True, text=True, capture_output=True)
        require(f"[GNUPG:] VALIDSIG {PROFILE['signer']} " in result.stdout, "Unexpected repository signer")
        # gpgv verifies a clear signature but does not extract its plaintext.
        body = release.read_text().split("\n\n", 1)[1].split("\n-----BEGIN PGP SIGNATURE-----", 1)[0]
        text = "\n".join(line.removeprefix("- ") for line in body.splitlines())
        signed = paragraphs(text)[0]
        entries = [line.split() for line in signed["SHA256"].splitlines() if line.strip()]
        expected = [row for row in entries if row[2] == "main/binary-aarch64/Packages"]
        require(len(expected) == 1 and sha(index) == expected[0][0] and
                index.stat().st_size == int(expected[0][1]), "Unauthenticated Packages index")
        latest = {}
        for record in paragraphs(index.read_text()):
            name = record["Package"]
            if name not in latest or version_matches(record["Version"], ">>", latest[name]["Version"]):
                latest[name] = record
        packages = closure(list(latest.values()))
        lock = {"schema": 1, "profile_sha256": sha(HERE / "profile.json"),
                "provenance": {"repository": PROFILE["repository"], "date": signed["Date"],
                               "signer": PROFILE["signer"], "inrelease_sha256": sha(release),
                               "index_sha256": sha(index)},
                "packages": [{key: record[key] for key in FIELDS if key in record} for record in packages]}
        atomic_write(args.lock, (json.dumps(lock, indent=2) + "\n").encode())
        atomic_write(args.lock.with_suffix(".InRelease"), release.read_bytes())
        print(f"Authenticated and locked {len(packages)} packages from {signed['Date']}")


def load_lock(path):
    lock = json.loads(path.read_text())
    require(lock["schema"] == 1 and lock["profile_sha256"] == sha(HERE / "profile.json"), "Stale lock/profile")
    require(lock["provenance"]["repository"] == PROFILE["repository"] and
            sha(path.with_suffix(".InRelease")) == lock["provenance"]["inrelease_sha256"],
            "Lock provenance differs from saved signed snapshot")
    records = lock["packages"]
    require(closure(records) == records, "Lock contains unused or unordered packages")
    for record in records:
        require(re.fullmatch(r"pool/[a-zA-Z0-9_+.:/-]+\.deb", record["Filename"]) and
                ".." not in PurePosixPath(record["Filename"]).parts, "Unsafe package filename")
    return lock


def check_debs(packages, files):
    """Check extraction paths before upstream tar runs; no target code is executed."""
    paths, links = set(), set()
    for record, deb in zip(packages, files):
        with subprocess.Popen(["dpkg-deb", "--ctrl-tarfile", str(deb)], stdout=subprocess.PIPE) as process:
            with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                for member in archive:
                    name = member.name.removeprefix("./")
                    require((member.isdir() and name in ("", ".")) or
                            (member.isfile() and "/" not in name and name not in ("", ".", "..")),
                            f"Unsafe control archive entry: {member.name}")
            require(process.wait() == 0, "dpkg-deb control inspection failed")
        metadata = subprocess.check_output(["dpkg-deb", "--field", str(deb)], text=True)
        actual = paragraphs(metadata)[0]
        for field in ("Package", "Version", "Architecture", "Depends", "Pre-Depends"):
            require(actual.get(field) == record.get(field), f"Package metadata mismatch: {record['Package']} {field}")
        with subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(deb)], stdout=subprocess.PIPE) as process:
            with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                for member in archive:
                    name = member.name.removeprefix("./").rstrip("/")
                    if name in ("", ".") and member.isdir():
                        continue
                    require(not name.startswith("/") and ".." not in PurePosixPath(name).parts,
                            f"Unsafe package path: {name}")
                    require(PREFIX.strip("/").startswith(name + "/") or name == PREFIX.strip("/") or
                            name.startswith(PREFIX.lstrip("/")), f"Package writes outside prefix: {name}")
                    require(member.isfile() or member.isdir() or member.issym() or member.islnk(),
                            f"Unsupported package entry: {name}")
                    if member.issym():
                        links.add(name)
                    if member.islnk():
                        target = member.linkname.removeprefix("./")
                        require(target.startswith(PREFIX.lstrip("/")) and ".." not in PurePosixPath(target).parts,
                                f"Unsafe hardlink: {name}")
                    paths.add(name)
            require(process.wait() == 0, "dpkg-deb tar inspection failed")
    for name in paths:
        require(not any(str(parent) in links for parent in PurePosixPath(name).parents),
                f"Package extraction would traverse a symlink: {name}")


def canonicalize(source, output):
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as result:
        require("EXECUTABLES.txt" not in archive.namelist(), "Reserved executable manifest already exists")
        executables = {entry.filename for entry in archive.infolist()
                       if not entry.is_dir() and entry.filename != "SYMLINKS.txt" and (entry.external_attr >> 16) & 0o111}
        for name in sorted(archive.namelist() + ["EXECUTABLES.txt"]):
            data = ("\n".join(sorted(executables)) + "\n").encode() if name == "EXECUTABLES.txt" else archive.read(name)
            if name == "SYMLINKS.txt" or (name.startswith("var/lib/dpkg/info/") and name.endswith(".md5sums")):
                data = ("\n".join(sorted(data.decode().splitlines())) + "\n").encode()
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = 0o40700 if name.endswith("/") else (0o100700 if name in executables else 0o100600)
            info.external_attr = (mode << 16) | (0x10 if name.endswith("/") else 0)
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, data, compresslevel=9)


def elf_contract(data, name):
    require(data[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", data, 18)[0] == 183,
            f"Not little-endian ARM64 ELF: {name}")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    offset, size, count = header[5], header[10], header[11]
    require(count > 0 and size == 64 and offset + size * count <= len(data), f"Missing/invalid ELF section table: {name}")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * size) for i in range(count)]
    needed, apis = [], []
    for section in sections:
        kind, start, length = section[1], section[4], section[5]
        if kind == 6:  # SHT_DYNAMIC; sh_link identifies its string table.
            strings = sections[section[6]]
            for pos in range(start, start + length, 16):
                tag, value = struct.unpack_from("<qQ", data, pos)
                if tag == 1:
                    begin = strings[4] + value
                    needed.append(data[begin:data.index(b"\0", begin)].decode())
        if kind == 7:  # SHT_NOTE: Android ABI note, when supplied by the compiler.
            pos = start
            while pos < start + length:
                namesz, descsz, tag = struct.unpack_from("<III", data, pos)
                note_name = data[pos + 12:pos + 12 + namesz].rstrip(b"\0")
                desc = pos + 12 + ((namesz + 3) & ~3)
                if note_name == b"Android" and tag == 1 and descsz >= 4:
                    apis.append(struct.unpack_from("<I", data, desc)[0])
                pos = desc + ((descsz + 3) & ~3)
    return needed, apis


def verify(archive_path, lock):
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        for name in names:
            require(not name.startswith("/") and ".." not in PurePosixPath(name).parts, f"Unsafe ZIP path: {name}")
        executable_lines = archive.read("EXECUTABLES.txt").decode().splitlines()
        executables = set(executable_lines)
        require(executable_lines == sorted(executables), "Invalid executable manifest order/duplicates")
        require(all(name in names and not name.endswith("/") for name in executables), "Missing executable file")
        for entry in archive.infolist():
            if not entry.is_dir():
                require(bool((entry.external_attr >> 16) & 0o111) == (entry.filename in executables),
                        f"Executable metadata mismatch: {entry.filename}")
        links = {}
        for line in archive.read("SYMLINKS.txt").decode().splitlines():
            target, name = line.split("←")
            name = name.removeprefix("./")
            require(name not in links and name not in names and not name.startswith("/") and
                    ".." not in PurePosixPath(name).parts, f"Unsafe/duplicate symlink: {name}")
            links[name] = target
        def resolve(name):
            seen = set()
            while name in links:
                require(name not in seen, f"Symlink cycle: {name}")
                seen.add(name)
                target = links[name]
                name = (target[len(PREFIX):] if target.startswith(PREFIX) else
                        posixpath.normpath(posixpath.join(posixpath.dirname(name), target)))
                require(not name.startswith(("/", "../")), f"Symlink outside prefix: {name}")
            return name

        for command in PROFILE["commands"]:
            require(resolve(f"bin/{command}") in names, f"Missing command: {command}")
            require(resolve(f"bin/{command}") in executables, f"Command not executable: {command}")
        status = paragraphs(archive.read("var/lib/dpkg/status").decode())
        require(len(status) == len(lock['packages']) and
                {r['Package']: (r['Version'], r['Architecture'], r['Status']) for r in status} ==
                {r['Package']: (r['Version'], r['Architecture'], 'install ok installed') for r in lock['packages']},
                "Installed package database differs from lock")
        for record in lock["packages"]:
            require(f"var/lib/dpkg/info/{record['Package']}.list" in names, f"Missing dpkg file list: {record['Package']}")
        elf_count, fixtures = 0, []
        for name in names:
            data = archive.read(name)
            if data.startswith(b"\x7fELF"):
                elf_count += 1
                needed, apis = elf_contract(data, name)
                missing = [lib for lib in needed if lib not in SYSTEM_LIBS and resolve(f"lib/{lib}") not in names]
                if name.startswith("libexec/installed-tests/"):
                    fixtures.append(name)
                    if missing or any(api > 24 for api in apis):
                        print(f"Optional installed-test fixture: {name}; API notes={apis}; missing={missing}")
                else:
                    require(not any(api > 24 for api in apis), f"ELF requires Android >24: {name}")
                    require(not missing, f"Missing shared library: {name} -> {missing}")
        for record in lock["packages"]:
            info = f"var/lib/dpkg/info/{record['Package']}"
            for path in archive.read(info + ".list").decode().splitlines():
                if path.startswith(PREFIX):
                    relative = path[len(PREFIX):].rstrip("/")
                    require(not relative or relative in names or relative + "/" in names or relative in links,
                            f"Missing package-owned path: {path}")
            for line in archive.read(info + ".md5sums").decode().splitlines():
                digest, path = line.split(None, 1)
                require(path.startswith(PREFIX.lstrip("/")), f"Invalid dpkg checksum path: {path}")
                relative = path[len(PREFIX.lstrip("/")):]
                require(hashlib.md5(archive.read(relative)).hexdigest() == digest, f"dpkg checksum mismatch: {relative}")
        for service in ("sshd", "ssh-agent"):
            require(f"var/service/{service}/down" in names, f"Service unexpectedly enabled: {service}")
        print(f"Verified {len(status)} packages (file lists/checksums), {len(PROFILE['commands'])} commands, {len(links)} symlinks, {elf_count} ARM64 ELFs")
        print(f"Production ELF closure/API-24 notes checked; {len(fixtures)} optional installed-test ELFs inspected, not claimed runnable")
        print(f"ZIP bytes={archive_path.stat().st_size}; expanded bytes={sum(i.file_size for i in archive.infolist())}")


def build(args):
    lock = load_lock(args.lock)
    def recover():
        return snapshot.restore(args.lock, HERE / "profile.json", args.cache,
                                args.cache.parent / "snapshot-archives")

    sources = source_files(args.cache, args.offline, recover)
    files = [fetch(PROFILE["repository"] + "/" + r["Filename"], r["SHA256"], args.cache, args.offline, recover)
             for r in lock["packages"]]
    check_debs(lock["packages"], files)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="generate-", dir=args.output.parent) as directory:
        work = Path(directory).resolve()
        for name, source in sources.items():
            destination = work / "upstream" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        repo = work / "repository"
        for record, source in zip(lock["packages"], files):
            destination = repo / record["Filename"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        index = repo / "dists/stable/main/binary-aarch64/Packages"
        index.parent.mkdir(parents=True)
        index.write_text("\n\n".join("\n".join(f"{key}: {value}" for key, value in r.items())
                                    for r in lock["packages"]) + "\n\n")
        all_index = repo / "dists/stable/main/binary-all/Packages"
        all_index.parent.mkdir(parents=True)
        all_index.write_text("")
        for name in ("home", "tmp"):
            (work / name).mkdir()
        environment = {"PATH": os.environ["PATH"], "HOME": str(work / "home"), "TMPDIR": str(work / "tmp"),
                       "LC_ALL": "C", "TZ": "UTC"}
        subprocess.run(["bash", str(work / "upstream/scripts/generate-bootstraps.sh"), "--pm", "apt",
                        "--architectures", "aarch64", "--add", ",".join(PROFILE["additional_packages"] + PROFILE["supplemental_packages"]),
                        "--repository", repo.as_uri()], cwd=work, env=environment, umask=0o077, check=True)
        canonical = work / "canonical.zip"
        canonicalize(work / "bootstrap-aarch64.zip", canonical)
        verify(canonical, lock)
        os.replace(canonical, args.output)
    print(f"bootstrap sha256={sha(args.output)}; Python={sys.version.split()[0]}; zlib={zlib.ZLIB_RUNTIME_VERSION}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("refresh", "build", "verify"):
        child = sub.add_parser(command)
        child.add_argument("--lock", type=Path, default=HERE / "bootstrap.lock.json")
        if command != "verify":
            child.add_argument("--cache", type=Path, required=True)
        if command == "refresh":
            child.add_argument("--metadata", type=Path, required=True, help="Directory containing InRelease and Packages")
        else:
            child.add_argument("--output", type=Path, required=True)
        if command == "build":
            child.add_argument("--offline", action="store_true", help="Reject every missing cached input")
    args = parser.parse_args()
    if args.command == "verify":
        verify(args.output, load_lock(args.lock))
    elif args.command == "build":
        build(args)
    else:
        refresh(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError, zipfile.BadZipFile) as error:
        sys.exit(f"bootstrap: {error}")
