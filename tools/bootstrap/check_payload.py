#!/usr/bin/env python3
"""Negative controls against a real generated payload (no target execution)."""
import argparse
import copy
from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
import zipfile

import bootstrap as b


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    lock = b.load_lock(b.HERE / "bootstrap.lock.json")
    b.verify(args.archive, lock)
    # Mutations reach independent artifact contracts, not mock implementations.
    cases = [
        ("bin/jq", None, "Missing command: jq"),
        ("bin/lsof", None, "Missing command: lsof"),
        ("lib/libmount.so", None, "Missing shared library"),
        ("bin/git", "wrong-arch", "Not little-endian ARM64 ELF"),
        ("var/lib/dpkg/status", "wrong-version", "Installed package database differs from delivery manifest"),
    ]
    with tempfile.TemporaryDirectory(dir=args.archive.parent) as directory, zipfile.ZipFile(args.archive) as source:
        for name, mutation, expected in cases:
            b.require(name in source.namelist(), f"Negative-control input not present: {name}")
            destination = Path(directory) / "mutated.zip"
            with zipfile.ZipFile(destination, "w") as output:
                for entry in source.infolist():
                    if entry.filename == name and mutation is None:
                        continue
                    data = source.read(entry)
                    if entry.filename == "EXECUTABLES.txt" and mutation is None:
                        data = ("\n".join(line for line in data.decode().splitlines() if line != name) + "\n").encode()
                    if entry.filename == name and mutation == "wrong-arch":
                        data = data[:18] + b"\x3e\x00" + data[20:]  # EM_X86_64
                    elif entry.filename == name and mutation == "wrong-version":
                        data = data.replace(b"Version: ", b"Version: 9999.", 1)
                    metadata = copy.copy(entry)
                    metadata.compress_type = zipfile.ZIP_STORED
                    output.writestr(metadata, data)
            try:
                with redirect_stdout(io.StringIO()):
                    b.verify(destination, lock)
            except ValueError as error:
                b.require(expected in str(error), f"Negative control failed for wrong reason: {error}")
            else:
                raise ValueError(f"Negative control unexpectedly passed: {name}")
            print(f"Rejected {name}: {expected}")


if __name__ == "__main__":
    main()
