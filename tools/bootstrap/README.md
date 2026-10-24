# Offline default tools

This is the Fold **candidate** bootstrap on `fold/default-tools`; it becomes the
`fold/main` default only on merge. No version bump, APK publication, or installation
is implied.

Fresh ARM64/API-24+ installations get the usual Termux Android-7 apt bootstrap
plus **Git, OpenSSH (ssh/scp/sftp), tmux, jq, ripgrep (`rg`), fd, fzf, rsync, tree,
zip, and wget**, without first-launch network access. Bash, coreutils, curl,
unzip, nano, apt/dpkg, CA certificates, and the other upstream baseline packages
remain. This is native Android/Bionic software for `com.termux` and
`/data/data/com.termux/files/usr`, not Linux/glibc binaries.

## Existing installations and boundaries

An APK update **does not replace, reset, or upgrade a nonempty existing prefix**.
The unchanged `TermuxInstaller` early return preserves installed packages and
configuration. Do not uninstall, delete `$PREFIX`, or run a bootstrap script to
obtain these defaults. In an existing terminal, explicitly run:

```sh
pkg update
pkg install git openssh tmux jq ripgrep fd fzf rsync tree zip wget
```

Review apt's proposed changes. This uses the user's current repository and
package versions, not the APK snapshot, and can update dependencies as required.
A full `pkg upgrade` remains a separate explicit maintenance choice.

OpenSSH's official package also contains `sshd` and service definitions: there
is no separate official client-only package. Neither sshd nor ssh-agent starts
automatically. Both service definitions retain their `down` files and
`termux-services`/runit are absent. On the device, upstream's second stage runs
package `postinst` scripts offline; OpenSSH creates host keys and the private
`~/.ssh/authorized_keys` placeholder then, **not during APK construction**. No
keys, accounts, tokens, Pi configuration, or user dotfiles are baked in. Additional
packages have no other postinst/preinst scripts in this snapshot.

Node.js, Python, compilers, and versioned native development adapters belong to
the separately managed development environment, **not the APK**. Android API
services still require the separately installed, compatibly signed Termux:API
app and an explicit `pkg install termux-api`; bundling shell tools does not grant
Android permissions. Optional Git Perl helpers and development self-tests are
not a promise of a bundled language/toolchain runtime.

## Delivery and trust

- `profile.json` pins a full upstream commit and SHA-256 for the generator,
  properties, both second-stage scripts, required `repo.json`, and official
  **public verification key**. We download those small files, not a fork of the
  package build system. The complete generator and properties must be reviewed
  when their pins change; properties supports sourcing `termuxrc`, so the runner
  gives it an empty private HOME and a minimal environment.
- `refresh` authenticates a supplied `InRelease` with gpgv and that independently
  pinned Termux Releases public key, verifies the signed SHA-256/length of the
  full aarch64 `Packages` index, and only then writes a reviewed lock. The lock
  records the signer, date, signed-release/index digests, and each package's exact
  filename (URL under the pinned repository), version, architecture, size,
  SHA-256, and dependency/conflict metadata. The small signed release is retained;
  a giant redundant package index is not vendored. Archive the matching full index
  externally if historical metadata revalidation will be needed after mirror
  rotation. Current production builds **never fetch an index or resolve latest**.
- There are 110 locked packages. The generator chooses the first dependency
  alternative; our validator requires that choice and verifies Debian version
  constraints with host dpkg. Missing dependencies, unsupported syntax or
  Pre-Depends, conflicts, wrong architecture, and development/service-manager
  packages fail closed. `libmount` (and its `libblkid` dependency) is an explicit
  supplemental root because this snapshot's util-linux `lsns` needs it but does
  not declare it. This avoids silently shipping a broken inherited command.
- Every downloaded/cached source and **every .deb** is SHA-256 checked before the
  upstream shell runs or packages are inspected/extracted. Bad cache files fail
  instead of being silently trusted/refreshed. HTTPS redirects cannot downgrade
  transport. Temporary downloads are renamed only after verification. The build
  constructs a private `file://` repository from locked records and verified
  packages; upstream cannot fall back to a live apt repository.
- All generator source, HOME, TMPDIR, repository, unpacked native files, and
  partial archives stay under the requested build directory. No target binary,
  maintainer script, or second-stage script is executed by the build. Package
  data paths, symlink ancestors, and control-archive entries are checked before
  upstream extraction. The final ZIP is validated then atomically replaced; failures preserve the prior
  output. Gradle revalidates cached inputs on every invocation, not just clean
  builds, and embeds only `app/build/bootstrap/bootstrap-aarch64.zip` via an
  absolute assembler input path (stale source-tree ZIPs cannot shadow it). The ZIP
  is an explicit prerequisite of its assembly object: Clang's depfile omits
  `.incbin` inputs, so tracking only the shared library can retain stale bytes
  after a no-clean lock refresh. AGP already delegates incrementality to NDK;
  no extra Gradle task hook is needed. `clean` removes build output normally.
- ZIP member order, timestamps (1980-01-01), permissions, extra fields, comments,
  symlink-table order, and generated dpkg checksum-list order are canonicalized.
  A fixed private umask preserves upstream owner-execute bits independently of
  the caller's umask. `EXECUTABLES.txt` carries these bits through Java's
  `ZipInputStream`, which cannot expose Unix ZIP attributes. The focused
  `BootstrapArchive` staging extractor restores only listed regular files'
  owner-execute bit before the existing installer creates symlinks/renames staging;
  no broad `bin/` heuristic or per-package chmod list remains. Service scripts and
  Git hook templates stay executable, ordinary files do not gain execute, and
  `down` files still keep services disabled. Missing/unsafe executable metadata
  fails before installation commits.
  DEFLATE level 9 keeps the whole-blob JNI/Java bootstrap allocation bounded:
  this snapshot is 60,690,486 compressed bytes / 151,375,877 expanded bytes on
  Python 3.14.8 + zlib 1.3.2. **Use the same Python/zlib toolchain for byte-for-byte
  reproduction**; the runner prints both versions. CI compares two independent
  generations, the second offline, as well as retaining the two unsigned APK
  builds. No uncompressed 151 MB blob is loaded just to chase cross-zlib identity.
- Validation checks the exact dpkg package/version/architecture inventory,
  package-owned paths and file checksums, requested commands and symlinks, every
  ELF's ARM64 architecture, and every production ELF's NEEDED-library closure and
  available Android ABI notes (no production note above API 24). The official
  Android-7 repository is the compatibility authority for binaries without ABI
  notes; this is not an Android-7 device execution claim.

Upstream packages preserve **nine ELF developer fixtures** beneath
`libexec/installed-tests/`: two termux-core unit tests; termux-exec runtime tests
with/without sanitizers at API 24 and 28, two unit tests, and `print-args-binary`.
They are inspected, retained with intact package metadata, and excluded only from
the *production runnable/API-24* claim. Four need an absent ASan library; two
runtime fixtures explicitly target API 28 (one is also ASan). The runner reports
all five affected paths and reasons. No compiler/ASan runtime is added for them.
The two service logger symlinks are likewise dormant until a user explicitly
installs `termux-services`; normal command/library symlinks must resolve offline.

## Build and verification

On Linux/Termux, install host prerequisites **in your build environment**:
Python 3.11+, Bash, curl, dpkg/dpkg-deb, gpgv, binutils/ar, jq, zip, gzip, xz,
and standard coreutils/find/grep/sed/tar/awk/xargs utilities. The CI workflows
provision these explicitly; signing remains isolated and unchanged. No live
Termux package installation is part of this project's build/test commands. On macOS,
put Bash 4+ and GNU coreutils/findutils/grep/sed/tar/awk/binutils ahead of the BSD
system tools and install dpkg/gpgv as build-host tools; upstream's generator does
not support stock Bash 3.2/BSD utilities. macOS execution is not validated here.

```sh
python3 -m unittest discover -s tools/bootstrap -p 'test_*.py' -v
python3 tools/bootstrap/bootstrap.py build --cache app/build/bootstrap/downloads \
  --output app/build/bootstrap/first/bootstrap-aarch64.zip
python3 tools/bootstrap/bootstrap.py build --offline --cache app/build/bootstrap/downloads \
  --output app/build/bootstrap/second/bootstrap-aarch64.zip
cmp app/build/bootstrap/{first,second}/bootstrap-aarch64.zip
python3 tools/bootstrap/check_payload.py app/build/bootstrap/first/bootstrap-aarch64.zip
python3 tools/bootstrap/check_native_incremental.py "$ANDROID_HOME/ndk/30.0.16248370"
./gradlew --no-daemon :app:assembleDebug
```

On an Android ARM64 build host only, an optional native smoke check extracts the
actual payload into a **new** private build directory and uses its own libraries,
HOME and temporary directory. It runs version/help commands, Git initialization,
JSON/search/filter operations, a local rsync copy, and a ZIP round trip. It also
checks generated Git hook samples' execute permissions, disabled service scripts,
and non-executable file delivery. It never runs postinst, starts a service, connects to a network, or replaces a prefix:

```sh
python3 tools/bootstrap/smoke_android.py app/build/bootstrap/first/bootstrap-aarch64.zip \
  --work app/build/bootstrap/native-smoke-new
```

The runner tests reject tampered sources/packages, missing offline inputs,
unsigned/changed release metadata, mismatched indices, incomplete dependencies,
unsupported alternatives/version constraints, and wrong architectures. Real
payload negative controls remove a command/library and alter ELF architecture or
dpkg versions. They must fail for the intended contract. The native gate invokes
real NDK Make rules on two ZIP inputs without clean/source changes and compares
actual embedded object bytes; on Termux it uses existing native CC/CXX/Python
instead of attempting the SDK's x86_64 programs. The app's plain-JUnit extraction
regressions run delivered scripts and a generated Git hook through the real
production staging extractor, and check non-executable files, service-down state,
and invalid metadata. Fold checks retain
wrapper validation, full unit tests, debug compilation, packaged identity/API/ABI
and unsigned-signature checks, and two clean reproducible unsigned APKs. Signed
release verification/publication remains a separate explicitly dispatched flow.

Physical offline fresh-bootstrap completion (including device second stage and
heap usage), Android-7 execution, and Fold8/Ultra acceptance remain post-install
gates, not claims inferred from host tests. Do not replace a real prefix to test.

## Explicit reviewed maintenance

Do not run refresh from CI/Gradle or on app launch. In an isolated build directory,
fetch one consistent metadata pair (retry the pair if the mirror rotates):

```sh
mkdir -p build/bootstrap-review
curl --fail --location --proto '=https' --proto-redir '=https' \
  https://packages-cf.termux.dev/apt/termux-main/dists/stable/InRelease \
  -o build/bootstrap-review/InRelease
curl --fail --location --proto '=https' --proto-redir '=https' \
  https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages \
  -o build/bootstrap-review/Packages
python3 tools/bootstrap/bootstrap.py refresh --metadata build/bootstrap-review \
  --cache build/bootstrap-review/downloads
```

Review the lock/provenance diff, package scripts (especially service/network
behavior), upstream source changes, dependency/API/ELF closure, native command
smoke proof, compressed size, and two clean build results; update this document
and `FORK_CHANGES.md` together. Committed hashes supply integrity, not availability:
if a mirror prunes a locked filename, builds fail. Preserve a verified download
cache or use an explicitly reviewed HTTPS snapshot mirror with the same hashes;
never silently substitute newer packages. Do not commit packages/APKs or private
build/device data. Release versions remain centrally owned, not bumped here.
