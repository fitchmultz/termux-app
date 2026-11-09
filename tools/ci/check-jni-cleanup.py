#!/usr/bin/env python3
"""Compile/run included-source JNI fault tests on the Linux CI host, not Android.

Requires a host C compiler and libc development headers plus Java 21 JAVA_HOME.
The actual termux.c is included by jni_cleanup_harness.c, with fake JNI callbacks,
tracked allocators and PTM/fork stubs. It never starts a terminal subprocess.
A separate full NDK build remains required; this is not an ART or socket test.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import resource
import shlex
import shutil
import subprocess
import sys
import tempfile


def run(command: list[str], *, env: dict[str, str], timeout: int = 90) -> None:
    print("+ " + shlex.join(command), flush=True)
    subprocess.run(command, env=env, check=True, timeout=timeout)


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC", "gcc"), help="host C compiler executable")
    parser.add_argument("--source", type=Path,
                        default=root / "terminal-emulator/src/main/jni/termux.c",
                        help="actual termux.c to include (can select a saved baseline for RED evidence)")
    parser.add_argument("--case", help="run one named harness case, plus the pending-exception helper")
    parser.add_argument("--sanitizers", choices=("auto", "require", "none"), default="auto",
                        help="use ASan/UBSan if an independent trivial probe can compile and run")
    args = parser.parse_args()
    if not sys.platform.startswith("linux"):
        parser.error("this CI harness requires a Linux host (not an Android binary or emulator)")
    compiler = shutil.which(args.cc)
    if compiler is None:
        parser.error(f"host compiler unavailable: {args.cc}; install gcc and libc6-dev in the test container")
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        parser.error("JAVA_HOME must select the CI Java 21 installation")
    include = Path(java_home) / "include"
    if not (include / "jni.h").is_file() or not (include / "linux/jni_md.h").is_file():
        parser.error(f"JNI headers missing from JAVA_HOME: {include}")
    source = args.source.resolve(strict=True)
    harness = root / "tools/ci/jni_cleanup_harness.c"
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    env = dict(os.environ, ASAN_OPTIONS="detect_leaks=1:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    base = [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-g", "-O1"]
    sanitizer_flags = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    with tempfile.TemporaryDirectory(prefix="termux-jni-cleanup-") as temporary:
        work = Path(temporary)
        if args.sanitizers != "none":
            probe_source = work / "sanitizer-probe.c"
            probe_binary = work / "sanitizer-probe"
            probe_source.write_text("int main(void) { return 0; }\n")
            try:
                run(base + sanitizer_flags + [str(probe_source), "-o", str(probe_binary)], env=env)
                run([str(probe_binary)], env=env, timeout=20)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                if args.sanitizers == "require":
                    raise
                print("ASan/UBSan unavailable for independent trivial host probe; "
                      "running tracked-allocation harness UNSANITIZED", flush=True)
                sanitizer_flags = []
            else:
                print("ASan/UBSan enabled; a harness failure will not be retried unsanitized", flush=True)
        else:
            sanitizer_flags = []
            print("Sanitizers explicitly disabled; tracked-allocation checks remain active", flush=True)
        binary = work / "jni-cleanup-harness"
        literal = str(source).replace("\\", "\\\\").replace('"', '\\"')
        command = base + sanitizer_flags + [
            "-I" + str(include), "-I" + str(include / "linux"),
            '-DTERMUX_JNI_SOURCE="' + literal + '"', str(harness), "-o", str(binary),
        ]
        run(command, env=env)
        run([str(binary)] + ([args.case] if args.case else []), env=env, timeout=20)
    print("Included-source JNI fault harness passed. Full NDK, ART, process-lifecycle and "
          "real-socket integration remain separate gates.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
