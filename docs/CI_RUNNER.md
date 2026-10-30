# M1 Actions runner

All eight jobs in `run_tests.yml`, `signed_release.yml`, and
`termux_api_companion.yml` are configured for `[self-hosted, Linux, ARM64]`.
The owner supplies one repository Linux worker in a persistent Ubuntu 24.04
ARM64 VM on a private M1 Mac. Its single job slot serializes these jobs.
This configuration does not re-enable Actions: the owner does that separately.
If that runner is offline or busy, jobs queue; there is **no hosted fallback**.
The required ARM64 library mounts make an x86_64-hosted fallback incompatible.

## Execution environment

Every job uses `--platform linux/amd64` with:

```text
ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
```

The existing VM's Rosetta/binfmt support executes the amd64 userland and Android
host tools. The Actions runner itself remains ARM64. Its normal injected Node
runtime uses two read-only host-library mounts:

```text
/lib/ld-linux-aarch64.so.1:/lib/ld-linux-aarch64.so.1:ro
/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:ro
```

No workflow hardcodes a runner Node directory. The image is immutable, but the
mounted runtime libraries and runner software still belong to the maintained
host; this is not a wholly hermetic machine image. Steps assert `RUNNER_OS=Linux`,
`RUNNER_ARCH=ARM64`, and userland `uname -m=x86_64`. Bash is the run shell, with
`SHELL=/bin/bash` for the retained NDK's shell inspection.

Java setup explicitly selects Temurin **x64**, retaining major 21 for the main
project/SDK/API consumer and major 11 for the unchanged companion producers.
NDK 21.1 and r30 launchers inspect the shell's host architecture before Make;
individual amd64 binary translation on a native aarch64 shell was not enough.
The amd64 job userland preserves their existing Linux x86_64 tool paths, recipes,
and producer ABIs without SDK patches or architecture-spoofing wrappers.

## Tools and persistent state

The minimal container is not the old hosted tool image. Inline first steps run
root `apt-get` **inside the job container**, before checkout or tool-installing
actions. They provide Git/CA certificates where checkout is needed; curl/unzip,
Python, dpkg/gpgv/binutils, ZIP/compression tools, jq, Make, `file`, and the x64
C++ runtime where builds need them. Jobs using Java setup also install GnuPG
before the action so its default JDK signature verification is not silently
skipped on the minimal image. No host package/repository configuration is changed.
Make serves the actual native incremental gate; `file` serves NDK 21.1's 64-bit
shell check. Existing SDK, NDK, Gradle, library and action pins remain.

Signers install [GitHub CLI 2.103.2](https://github.com/cli/cli/releases/tag/v2.103.2)
from the official `gh_2.103.2_linux_amd64.tar.gz`, not Ubuntu's older package.
The inline workflow checks its 15,031,001-byte size and SHA-256 before extraction:

```text
62ccf747115ddc61237efffdde66a3c8f70ba249433dc64f70801089b59c59c8
```

The release fixes attachment-token authorization; it does not change this
workflow's artifact-envelope/release gates. No downloaded shell script is run.

Build/validation jobs with generated payloads and both signers create a new
private directory beneath runner temporary storage. SDK installers receive
job-specific `ANDROID_SDK_ROOT` and `ANDROID_HOME`; main Gradle jobs also receive a job-specific `GRADLE_USER_HOME`.
Companion source rounds retain six separate Gradle homes and two empty local
Maven repositories, with build/configuration caches disabled. No shared Actions
cache is added. Java's runner tool cache may persist, separated by architecture.
Bootstrap inputs are still rehashed; its second generation deliberately reuses
the first generation's verified download cache offline.

Signers have no repository checkout, Gradle invocation or cache restore. Their
candidate/output directories are below their new job root, not an old workspace.
Signing policy and validators remain reviewed workflow literals; APK/manifest
artifacts are data, never executable scripts. The companion's strict schema 3
records the registered runner separately from container/platform/Java execution
and still verifies selected SDK/JDK records and actual verification-tool hashes.
Container identity supplements those hashes; it does not replace them.

A **fresh job/container is not a fresh machine**. The VM, runner workspace,
mounted storage and tool cache persist. Traps and `always()` steps remove key and
password files on ordinary completion/failure/cancellation; a hard termination
can leave files requiring runner cleanup. Container root can also leave
root-owned bind-mounted files, so runner ownership/temporary-file cleanup remains
an infrastructure responsibility. No new ephemeral-host or separate-machine
signing requirement is implied.

## Workflow and release boundaries

PR workflows still run `pull_request`, not `pull_request_target`, with read-only
repository access and no signing environment. PR-controlled workflow/build/test
code executes on the self-hosted service; the private location does not make it
trusted or reset persistent storage. Existing repository workflow/dispatch and
protected-environment controls remain necessary. Do not claim that job containers
establish independent host isolation.

The main release requires explicit dispatch and signs in its separate
`termux-fold-signing` job; public publication is a separate **default-false**
input. With publication off, the verified signed APK, checksum and provenance
are downloadable in an authenticated Actions artifact using the repository's
retention setting. Companion signing is **default-false**, dispatch-only, `fold/main`-only and
repository-bound, in the same protected environment. The key remains in its
existing protected GitHub secrets; no local backup is an input to these jobs.
Neither workflow installs an APK or grants Android permissions.

Wrapper validation, bootstrap failure/negative/offline checks, full main tests,
native A-to-B incremental rebuild, debug compilation, packaged identity/SDK/ABI
and unsigned-signature gates, and two reproducible unsigned main builds remain.
The companion retains two complete source chains, publication/resolution/source
preservation audits, byte-identical unsigned APKs, bounded same-run artifacts,
and independent optional signature/certificate/archive validation. API unit
sources remain absent; `NO-SOURCE` is not an additional passing suite.

Git commands that read the checkout for main release identity and reproducibility
pass `safe.directory` only for the current workspace, avoiding the container's
host-owned checkout error without changing host or global Git configuration.

## Creating a signed update

The main workflow builds the exact annotated tag, not the current `fold/main`
tree. Rebuilding an old tag cannot include later commits; untagged main changes
are not automatically signed or made downloadable.

1. Review and integrate the intended changes. Prepare a release profile in
   `build.gradle` with a `versionName` and a `versionCode` higher than the
   installed and previously released codes; update `FOLD_PROFILE.md` and
   `FORK_CHANGES.md` and pass the required checks. The current workflow accepts
   `0.119.0-fold.N` version names, where `N` is positive.
2. After the separate versioning decision, create and push a **new annotated**
   `fold-v<versionName>` tag on the final approved main commit. Never move or
   reuse an existing tag. An optional `-rcN` suffix is supported.
3. Run [Fold signed release](https://github.com/fitchmultz/termux-app/actions/workflows/signed_release.yml)
   from `fold/main`, set `release_tag` to that tag, and leave `publish_release`
   off. The protected environment supplies the existing key automatically.
4. On the successful run page, download `termux-fold-signed-<run>-<attempt>`
   from **Artifacts**. The ZIP contains the signed APK, `SHA256SUMS`, and
   `PROVENANCE`; GitHub sign-in is required. Artifacts use the repository's
   retention setting, currently 90 days. For CLI download, use
   `gh run download RUN_ID -R fitchmultz/termux-app --name termux-fold-signed-RUN_ID-ATTEMPT --dir DIR`,
   replacing the placeholders. In the extracted directory, run
   `sha256sum -c SHA256SUMS` and verify the APK's package, version, signing
   certificate and provenance against the installed app and approved tag.

The artifact-only path creates or changes no GitHub Release. Public publication
and physical installation remain separate decisions; see the
[installation boundary](../FOLD_PROFILE.md#installation-boundary).

## Qualification

The migration's M1 mechanism probe ran the runner's ARM64 Node 24 and an amd64
shell/`uname` in the same pinned container with the read-only library mounts.
Additional probes installed x64 Java 11/21 and the retained SDK/NDK packages,
then launched the ordinary NDK 21.1/r30 wrappers/compilers and APK tools. These
checks establish the mechanism and installer/tool-launch compatibility, **not**
a passing Actions run, Android build, signer, publication or device result.
Historical hosted results in the dependency/companion audits remain historical
evidence for their stated revisions. Full current-revision runner gates and a separately authorized
signing invocation remain unverified until actually run after owner re-enablement.
Physical installation/backup/recovery and Fold acceptance remain separate
owner-controlled steps; never execute SDK x86_64 tools on ARM64 Termux to replace
that qualification.
