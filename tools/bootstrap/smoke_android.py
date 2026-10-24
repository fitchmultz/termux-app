#!/usr/bin/env python3
"""Opt-in Android ARM64 command smoke checks in a NEW private extraction, never $PREFIX."""
import argparse
import os
from pathlib import Path
import platform
import subprocess
import zipfile

import bootstrap as b


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--work", type=Path, required=True, help="New build-private directory; must not exist")
    args = parser.parse_args()
    b.require(platform.machine() == "aarch64" and Path("/system/bin/linker64").exists(), "Requires Android ARM64")
    work = args.work.resolve()
    live_prefix = Path(b.PREFIX)
    b.require(work != live_prefix and live_prefix not in work.parents and not work.exists(),
              "Use a NEW directory outside the live prefix")
    b.verify(args.archive, b.load_lock(b.HERE / "bootstrap.lock.json"))
    work.mkdir(parents=True)
    prefix = work / "usr"
    with zipfile.ZipFile(args.archive) as archive:
        archive.extractall(prefix)
        executables = set(archive.read("EXECUTABLES.txt").decode().splitlines())
        for name in executables:
            (prefix / name).chmod(0o700)
        for entry in archive.infolist():
            if not entry.is_dir() and entry.filename not in executables:
                b.require(not os.access(prefix / entry.filename, os.X_OK), f"Unexpected execute permission: {entry.filename}")
        for line in archive.read("SYMLINKS.txt").decode().splitlines():
            target, name = line.split("←")
            destination = prefix / name.removeprefix("./")
            destination.parent.mkdir(parents=True, exist_ok=True)
            if target.startswith(b.PREFIX):
                target = str(prefix / target[len(b.PREFIX):])
            os.symlink(target, destination)
    for directory in ("home", "tmp"):
        (work / directory).mkdir()
    environment = {"HOME": str(work / "home"), "TMPDIR": str(work / "tmp"), "PATH": "/system/bin",
                   "LD_LIBRARY_PATH": str(prefix / "lib"), "LANG": "C.UTF-8", "GIT_CONFIG_NOSYSTEM": "1",
                   "GIT_TEMPLATE_DIR": str(prefix / "share/git-core/templates")}
    live_status = Path(b.PREFIX) / "var/lib/dpkg/status"
    before = b.sha(live_status)

    def run(name, *arguments, stdin=None, expected=0):
        command = ["/system/bin/linker64", str(prefix / "bin" / name), *map(str, arguments)]
        result = subprocess.run(command, env=environment, cwd=work, input=stdin, capture_output=True, text=True)
        text = result.stdout + result.stderr
        print(f"{name} {' '.join(map(str, arguments))}: exit={result.returncode}; {text.splitlines()[:2]}")
        b.require(result.returncode == expected, text)
        return text

    for name, arguments in [("git", ["--version"]), ("ssh", ["-V"]), ("tmux", ["-V"]), ("jq", ["--version"]),
                            ("rg", ["--version"]), ("fd", ["--version"]), ("fzf", ["--version"]),
                            ("rsync", ["--version"]), ("tree", ["--version"]), ("zip", ["-v"]), ("wget", ["--version"]),
                            ("zsh", ["--version"]), ("lsd", ["--version"]), ("bat", ["--version"]),
                            ("delta", ["--version"]), ("direnv", ["version"]), ("git-lfs", ["version"])]:
        run(name, *arguments)
    run("sftp", "-h", expected=1)
    run("scp", "-h", expected=1)
    run("git", "init", "-q", work / "repository")
    b.require((work / "repository/.git/config").is_file(), "git init did not create a repository")
    hooks = list((work / "repository/.git/hooks").glob("*.sample"))
    b.require(hooks and all(os.access(hook, os.X_OK) for hook in hooks), "Generated Git hook samples lost execute permission")
    for service in ("sshd", "ssh-agent"):
        b.require(os.access(prefix / f"var/service/{service}/run", os.X_OK) and
                  (prefix / f"var/service/{service}/down").is_file(), f"Service delivery/disabled state broken: {service}")
    print(f"Verified {len(hooks)} generated Git hook samples executable; service scripts executable but disabled")
    b.require(run("jq", "-r", ".answer", stdin='{"answer":42}\n').strip() == "42", "jq processing failed")
    (work / "evidence.txt").write_text("default-tools-offline\n")
    b.require("default-tools-offline" in run("rg", "default-tools-offline", work / "evidence.txt"), "rg search failed")
    b.require("evidence.txt" in run("fd", "--no-ignore", "evidence", work), "fd search failed")
    b.require(run("fzf", "--filter", "offline", stdin="online\noffline\n").strip() == "offline", "fzf filter failed")
    run("rsync", work / "evidence.txt", work / "copy.txt")
    b.require((work / "copy.txt").read_text() == "default-tools-offline\n", "rsync copy failed")
    run("zip", "-q", "-j", work / "evidence.zip", work / "evidence.txt")
    with zipfile.ZipFile(work / "evidence.zip") as archive:
        b.require(archive.read("evidence.txt") == b"default-tools-offline\n", "zip content differs")
    b.require(b.sha(live_status) == before, "Live dpkg status changed during smoke checks")
    print("PASS: extracted-payload native commands + local operations; no network/postinst/service execution; live dpkg status unchanged")


if __name__ == "__main__":
    main()
