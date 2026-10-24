#!/usr/bin/env python3
"""Build the real NDK assembly object A -> B without cleaning, then compare its bytes."""
import argparse
import hashlib
import io
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

import bootstrap as b


def payload(marker):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(zipfile.ZipInfo("native-input", (1980, 1, 1, 0, 0, 0)), marker)
    return output.getvalue()


def rodata(path):
    data = path.read_bytes()
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, header[5] + i * header[10]) for i in range(header[11])]
    strings = sections[header[12]]
    for section in sections:
        start = strings[4] + section[0]
        if data[start:data.index(b"\0", start)] == b".rodata":
            return data[section[4]:section[4] + section[5]]
    raise ValueError("Assembly object has no .rodata")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ndk", type=Path)
    args = parser.parse_args()
    root = b.HERE.parent.parent
    parent = root / "build/bootstrap-native-test"
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=parent) as directory:
        work = Path(directory)
        archive = work / "bootstrap-aarch64.zip"
        obj = work / "obj/local/arm64-v8a/objs/termux-bootstrap/termux-bootstrap-zip.o"
        command = ["make", "-f", str(args.ndk.resolve() / "build/core/build-local.mk"), "NDK_PROJECT_PATH=null",
                   f"APP_BUILD_SCRIPT={root / 'app/src/main/cpp/Android.mk'}", "APP_ABI=arm64-v8a",
                   "APP_PLATFORM=android-24", "APP_STL=none", "NDK_DEBUG=0", f"NDK_OUT={work / 'obj'}",
                   f"NDK_LIBS_OUT={work / 'libs'}", f"FOLD_BOOTSTRAP_DIR={work}", f"NDK_HOST_PYTHON={sys.executable}"]
        if platform.machine() == "aarch64" and Path("/system/bin/linker64").exists():
            # Read official NDK rules/headers, but NEVER execute its x86_64 host tools.
            command += ["HOST_ARCH=x86_64", f"TARGET_CC={shutil.which('clang')}",
                        f"TARGET_CXX={shutil.which('clang++')}"]
        command.append(str(obj))
        for marker in (b"payload-A", b"payload-B-with-different-bytes"):
            if obj.exists():
                # Make uses mtimes; give even second-resolution filesystems a new input.
                time.sleep(max(0, obj.stat().st_mtime + 2.1 - time.time()))
            expected = payload(marker)
            archive.write_bytes(expected)
            subprocess.run(command, cwd=root, check=True)
            b.require(rodata(obj) == expected + struct.pack("<I", len(expected)),
                      "Stale embedded bootstrap: native object did not follow changed ZIP without clean")
            print(f"Embedded {marker.decode()}: sha256={hashlib.sha256(expected).hexdigest()}", flush=True)
    print("PASS: actual NDK object rule rebuilt A -> B without cleaning or changing source/makefiles")


if __name__ == "__main__":
    main()
