#!/usr/bin/env python3
"""Run production JNI socket code against real Linux sockets with ASan/UBSan.

Requires g++, libc development headers and JAVA_HOME JNI headers. The harness
supplies JNI values, not an Android VM; full Android tests/builds remain separate.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path,
                        default=root / "termux-shared/src/main/cpp/local-socket.cpp")
    parser.add_argument("--case", choices=("close", "read"))
    args = parser.parse_args()
    if not sys.platform.startswith("linux"):
        parser.error("this harness uses Linux AF_UNIX sockets")
    include = Path(os.environ["JAVA_HOME"]) / "include"
    harness = root / "termux-shared/src/test/cpp"
    source = str(args.source.resolve(strict=True)).replace("\\", "\\\\").replace('"', '\\"')
    env = dict(os.environ, ASAN_OPTIONS="detect_leaks=1:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    with tempfile.TemporaryDirectory(prefix="termux-local-sockets-") as temporary:
        binary = str(Path(temporary) / "local-socket-test")
        command = [os.environ.get("CXX", "g++"), "-std=c++17", "-pthread", "-g", "-O1",
                   "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                   "-I" + str(harness), "-I" + str(include), "-I" + str(include / "linux"),
                   '-DLOCAL_SOCKET_SOURCE="' + source + '"',
                   str(harness / "local-socket-test.cpp"), "-o", binary]
        print("+ " + shlex.join(command), flush=True)
        subprocess.run(command, env=env, check=True, timeout=90)
        subprocess.run([binary] + ([args.case] if args.case else []),
                       env=env, check=True, timeout=20)


if __name__ == "__main__":
    main()
