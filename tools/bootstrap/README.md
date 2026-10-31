# Offline default tools

This is the Fold source profile's locked offline bootstrap. Source integration,
release versioning, signed APK publication, and physical installation/validation
remain separate steps; see [FOLD_PROFILE.md](../../FOLD_PROFILE.md).

Fresh ARM64/API-24+ installations get the usual Termux Android-7 apt bootstrap
plus **Git, OpenSSH (ssh/scp/sftp), tmux, jq, ripgrep (`rg`), fd, fzf, rsync, tree,
zip, wget, zsh with zsh-completions, lsd, bat, git-delta (`delta`), direnv,
git-lfs, chezmoi, and the termux-api client**, without first-launch network access. Bash, coreutils, curl,
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
pkg install git openssh tmux jq ripgrep fd fzf rsync tree zip wget zsh zsh-completions lsd bat git-delta direnv git-lfs chezmoi termux-api
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
maintainer scripts are reviewed below; none starts a service or downloads software.

Node.js, Python, compilers, and versioned native development adapters belong to
the separately managed development environment, **not the APK**. Android API
services still require the separately installed **same-Fold-key Termux:API app**;
the bundled client alone does not provide Android API access or grant
permissions. Optional Git Perl helpers and development self-tests are
not a promise of a bundled language/toolchain runtime.

## Companion package assessment

Bash stays the default login shell. These are available commands, not a managed
dotfile setup: no `chsh`, aliases, zsh completion initialization, direnv hook or
`allow`, `.envrc` execution, `git lfs install`, delta pager configuration, or
global Git configuration is performed. Version probes do not launch an
interactive zsh or evaluate project environments.

The seven companion roots add only `libcap` and `libgit2` to the previous closure.
The same authenticated **2026-10-24 14:22:36 UTC** repository snapshot retains all
110 earlier package records unchanged. Those nine companion packages total 14,905,984
downloaded `.deb` bytes and 53,977,088 declared installed-size bytes; these are
**not** ZIP sizes. On the Fold, two builds from the authenticated cached inputs were byte-identical:
SHA-256 `94f3d2546354a7a298021170c41c397ae512fa61070ae1c53013545954620f53`,
96,430,740 compressed bytes / 244,553,917 expanded file bytes, using Python
3.15.1 and zlib 1.3.2. The payload verifier checked 121 package inventories,
28 commands, 1,478 symlinks, and 454 ARM64 ELFs; nine inherited installed-test
ELFs were inspected but not claimed runnable. The bundled chezmoi executable
matches the installed 2.73.3-1 binary that runs on this Fold. The whole ZIP
crosses JNI as one Java byte array; physical fresh-bootstrap heap behavior has
not been measured.

Versions below are the authenticated Termux package versions, not a claim about
every upstream project's latest release. Corresponding upstream releases/sources
and declared licenses:

| Package | Version | Upstream source/release | License |
| --- | --- | --- | --- |
| zsh | 5.9.2-2 | [5.9.2 source](https://www.zsh.org/pub/zsh-5.9.2.tar.xz) | Zsh permissive custom `LICENCE` |
| zsh-completions | 0.36.0 | [0.36.0](https://github.com/zsh-users/zsh-completions/tree/0.36.0) | BSD-3-Clause |
| lsd | 1.2.0 | [v1.2.0](https://github.com/lsd-rs/lsd/tree/v1.2.0) | Apache-2.0 |
| bat | 0.26.1-1 | [v0.26.1](https://github.com/sharkdp/bat/tree/v0.26.1) | Apache-2.0 |
| git-delta | 0.20.1 | [0.20.1](https://github.com/dandavison/delta/tree/0.20.1) | MIT |
| direnv | 2.38.2-1 | [v2.38.2](https://github.com/direnv/direnv/tree/v2.38.2) | MIT |
| git-lfs | 3.8.1 | [v3.8.1](https://github.com/git-lfs/git-lfs/tree/v3.8.1) | MIT |
| libcap | 2.78 | [2.78 source](https://kernel.org/pub/linux/libs/security/linux-privs/libcap2/libcap-2.78.tar.xz) | BSD-3-Clause / GPL-2.0, per component |
| libgit2 | 1.9.7 | [v1.9.7](https://github.com/libgit2/libgit2/tree/v1.9.7) | GPL-2.0 with linking exception |

The reviewed [Termux build definitions, patches, and upstream source checksums](https://github.com/termux/termux-packages/tree/de1dd3d18e239ef514e5c55ad744e6485e408c65/packages)
correspond to the pinned source snapshot. Official package license files and
common-license symlinks are retained under `share/doc/` and `share/LICENSES/`.
The native Android-7 aarch64 packages (zsh-completions is architecture `all`)
retain their authenticated hashes and dpkg metadata; no build-host binaries,
SDK runtime assets, compiler, or runtime patches are substituted into the APK.

Actual new-package maintainer scripts have two offline effects:

- `git-delta.postinst` generates Bash, zsh, and fish completion files under
  `$PREFIX/share/`. It does not edit shell startup files or select a Git pager.
- `bat.postinst` registers a `pager` alternative at priority **10**; the inherited
  `less` alternative at priority **50** remains preferred in fresh auto mode.
  `bat.prerm` removes its registration. Metadata/scripts are not stripped to
  conceal this behavior.

The other seven shell companion packages have no maintainer scripts. Official zsh global
configuration adjusts the default prompt/command-not-found handler and sources
the existing Termux profile **only when the user chooses that shell**. No user
dotfiles or shell selection are changed.

The locked [chezmoi 2.73.3-1](https://github.com/twpayne/chezmoi/tree/v2.73.3)
package is included. Its authenticated `.deb` is 10,682,256 bytes and has no
maintainer scripts; its installed executable matches the version already running
on the Fold. Mise, Rust/compiler, Tcl/Tk, X11, and language-runtime stacks remain
separate development packages.

## Termux:API client and Android companion boundary

The signed snapshot includes `termux-api` **0.60.0**, aarch64, MIT, from
[termux-api-package v0.60.0](https://github.com/termux/termux-api-package/tree/v0.60.0).
Bash, util-linux, and termux-am (>= 0.8.0) satisfy its dependencies. Its `.deb` is 30,112
bytes, SHA-256 `b83c85e0b8f345ac4e99dd09698a8077712c85a736652bb38465cd0f2ed0a8ae`;
the declared installed size is 364 KiB, not the ZIP size or expanded-byte delta.
The full official client package is retained, including its other API wrappers,
library/header, and MIT notice under `share/doc/termux-api/copyright`.

The new command contracts are the canonical `$PREFIX/bin/termux-clipboard-get`
and `$PREFIX/bin/termux-clipboard-set`, not a user PATH shim. Both call
`$PREFIX/libexec/termux-api`, a symlink to `termux-api-broadcast`; there is no
`bin/termux-api` command. Actual payload checks and production Java extraction
prove these scripts/helper are delivered executable without invoking any API or
reading/writing the clipboard. The two new native ELFs need only Android libc and
carry API-24 notes.

The package has **no maintainer scripts, conffiles, startup hooks, or service
definitions**. Its explicit `termux-api-start`/`termux-api-stop` commands request
the Android companion's KeepAliveService; neither is run by bootstrap or checks.
This default does not install or auto-start the Android companion. Clipboard/API
access still needs a separately installed Termux:API APK signed with the **same
Fold key**, subject to Android access rules and only the permissions needed for
the chosen APIs. Do not mix stock F-Droid/GitHub plugin APKs with Fold-signed
Termux, uninstall Termux, or grant all permissions to obtain clipboard support.
Compatible companion delivery is a separate integration/release step.

This addition applies only to fresh prefixes. If an existing installation already
has the current client, no APT transaction is needed; installing it again cannot
replace a missing companion APK. Existing configuration and clipboard shims are
not changed, and live clipboard functionality is not established by these builds.

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
- There are 121 locked packages. The generator chooses the first dependency
  alternative; our validator requires that choice and verifies Debian version
  constraints with host dpkg. Missing dependencies, unsupported syntax or
  Pre-Depends, conflicts, wrong architecture, and development/service-manager
  packages fail closed. `libmount` (and its `libblkid` dependency) is an explicit
  supplemental root because this snapshot's util-linux `lsns` needs it but does
  not declare it. This avoids silently shipping a broken inherited command.
- Every downloaded/cached source and **every .deb** is SHA-256 checked before the
  upstream shell runs or packages are inspected/extracted. Bad cache files fail
  instead of being silently trusted/refreshed. HTTPS redirects cannot downgrade
  transport. Temporary downloads are renamed only after verification. If a locked
  download fails, a recorded [content-addressed input snapshot](https://github.com/fitchmultz/termux-app/releases/tag/fold-bootstrap-inputs)
  restores that lock's exact inputs: the archive and every member are checked
  against reviewed hashes before the normal builder rechecks its cache. Unknown
  locks retain the original download path; `--offline` never downloads a snapshot.
  The build constructs a private `file://` repository from locked records and
  verified packages; upstream cannot fall back to a live apt repository.
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
  The Fold-native builds measured above use DEFLATE level 9: 96,430,740
  compressed bytes / 244,553,917 expanded bytes on Python 3.15.1 and zlib 1.3.2.
  **Use the same Python/zlib toolchain for byte-for-byte reproduction**; the
  runner prints both versions. CI compares two independent generations, the
  second offline, as well as retaining the two unsigned APK builds. The JNI
  interface returns the compressed ZIP as a Java byte array.
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
provision these explicitly in pinned Linux amd64 job containers on the private
M1 runner; see [runner policy and qualification limits](../../docs/CI_RUNNER.md).
Signing remains in separate environment-scoped jobs on the persistent runner. No live
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
and non-executable file delivery. It never invokes API/clipboard commands, runs
postinst, starts a service, connects to a network, or replaces a prefix:

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
and `FORK_CHANGES.md` together. After the locked inputs are in the verified cache,
create a snapshot before the mirror rotates:

```sh
python3 tools/bootstrap/bootstrap.py build --cache build/bootstrap-review/downloads \
  --output build/bootstrap-review/bootstrap-aarch64.zip
python3 tools/bootstrap/snapshot.py create --metadata build/bootstrap-review \
  --cache build/bootstrap-review/downloads --output build/bootstrap-review/inputs.tar
sha256sum build/bootstrap-review/inputs.tar tools/bootstrap/bootstrap.lock.json
```

The command re-authenticates the signed index, reproduces the lock, and checks
all archive members. Publish the tar as a new `bootstrap-inputs-<archive-sha256>.tar`
asset on the dedicated input prerelease, never replacing an existing asset; add
its archive SHA-256 under the lock SHA-256 in `snapshots.json` in the same reviewed
change as the new lock. Verify the downloaded asset and full build gates. This
archive contains public source/package inputs, not an APK or signing material.
Never silently substitute newer packages or commit packages/APKs or private
build/device data. Release versions remain centrally owned, not bumped here.
