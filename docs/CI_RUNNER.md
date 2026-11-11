# GitHub Actions runners

Every job in `run_tests.yml`, `bootstrap_validation.yml`, `signed_release.yml`, and
`termux_api_companion.yml` runs on a GitHub-hosted `ubuntu-24.04` x64 runner.
GitHub starts a new virtual machine for each job and discards it when the job
ends, so no workspace, tool cache or temporary file carries over between jobs.

## Execution environment

Every job runs in this digest-pinned Ubuntu 24.04 container:

```text
ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
```

The digest names a multi-platform image; the x64 runner selects its
`linux/amd64` variant. The container userland, the runner's injected Node and
the Android host tools all run natively as amd64. The image is immutable, but
the runner software and VM image belong to GitHub and the packages below come
from Ubuntu's current signed archive; this is not a wholly hermetic machine
image. Bash is the run shell, with `SHELL=/bin/bash` for the retained NDK's
shell inspection.

Java setup installs Temurin for the runner's x64 architecture: major 21 for the
main project/SDK/API consumer and major 11 for the unchanged companion producers.

## Tools and job state

The job container is minimal: the tools preinstalled on the hosted VM are not
available inside it. Inline first steps run root `apt-get` **inside the job
container**, before checkout or tool-installing actions. They provide Git/CA
certificates where checkout is needed; curl/unzip, Python, dpkg/gpgv/binutils,
ZIP/compression tools, jq, Make, `file`, and the x64 C++ runtime where builds
need them. Jobs using Java setup also install GnuPG before the action so its
default JDK signature verification is not silently skipped on the minimal image.
Make serves the actual native incremental gate; `file` serves NDK 21.1's 64-bit
shell check.

Signers install [GitHub CLI 2.103.2](https://github.com/cli/cli/releases/tag/v2.103.2)
from the official `gh_2.103.2_linux_amd64.tar.gz`, not Ubuntu's older package.
The inline workflow checks its 15,031,001-byte size and SHA-256 before extraction:

```text
62ccf747115ddc61237efffdde66a3c8f70ba249433dc64f70801089b59c59c8
```

The release fixes attachment-token authorization; it does not change this
workflow's artifact-envelope/release gates. No downloaded shell script is run.

Each job creates a private working directory beneath runner temporary storage,
exported as `FOLD_CI_WORK`, for generated payloads, signing inputs and outputs.
setup-android installs the SDK into its default location under the job's home
directory and exports `ANDROID_HOME`. The combined main test/build job uses
Gradle's default user home. Gradle's setup action validates the wrapper and
restores reusable Gradle state from the Actions cache; its default-branch jobs
save updated state, while PR jobs read it without publishing their own. The main
build enables Gradle's task cache.
Companion source rounds retain six separate Gradle homes and two empty local Maven
repositories, with build/configuration caches disabled. Release reproducibility
builds do not restore this cache or enable the task cache. Bootstrap inputs are still rehashed;
its second generation deliberately reuses the first generation's verified
download cache offline.

The combined main test/build job and unprivileged release-build test may use a
job-private Maven-layout seed from [CI input assets](https://github.com/fitchmultz/termux-app/releases/tag/ci-robolectric-cache-1.0.19).
Gradle may use it as a distinct local repository only for
`org.robolectric:nativeruntime-dist-compat:1.0.19`; its unmodified JAR and POM
are checked against [Google's independent SHA-256 pins](https://github.com/google/vtfx/blob/823068d39d49677b8be03779f4592232c5b8fdc6/gradle/verification-metadata.xml#L4131-L4136).
The seed also contains the Android 7, 9 and 17 `android-all-instrumented` JARs
used by the tests, checked against [Robolectric's own SHA-256 pins](https://github.com/robolectric/robolectric-bazel/blob/118b7fd904e781038051b99229fe2b0701e1e20a/bazel/robolectric.bzl#L28-L84).
When a runtime seed is available, test JVMs use this directory as
[`maven.repo.local`](https://github.com/robolectric/robolectric/blob/robolectric-4.17/plugins/maven-dependency-resolver/src/main/java/org/robolectric/internal/dependency/MavenDependencyResolver.java#L130-L142):
[Robolectric's native resolver](https://github.com/robolectric/robolectric/blob/robolectric-4.17/plugins/maven-dependency-resolver/src/main/java/org/robolectric/internal/dependency/MavenArtifactFetcher.java#L65-L79)
uses cached JARs and fetches missing versions normally. This does not enable
Robolectric's all-or-nothing offline resolver. Unavailable assets retain the
normal fallback; a checksum mismatch fails the job. The combined PR/main Gradle
invocation and a tagged-source build without a reusable main APK use the seed
for the test dependency and test JVMs. The clean reproducibility build does not
use the init script. Companion source rounds and signers do not use this seed.

Signers have no repository checkout, Gradle invocation or cache restore. Their
candidate/output directories are below their job's working directory.
Signing policy and validators remain reviewed workflow literals; APK/manifest
artifacts are data, never executable scripts. The companion's strict schema 5
records the GitHub-hosted `ubuntu-24.04` X64 runner separately from
container/platform/Java execution and still verifies selected SDK/JDK records
and actual verification-tool hashes.
Container identity supplements those hashes; it does not replace them.

Build and signing jobs run on separate VMs; a signer receives only the build
job's uploaded artifact. Traps remove key and password files when the signing
step exits, including on failure or cancellation, and an `always()` step repeats
the removal.

## Workflow and release boundaries

Fold checks retain both `fold/**` push and `fold/main` pull-request triggers.
Their shared concurrency key uses the source repository and source branch for
both events. With `cancel-in-progress: true`, a new push or PR run replaces older
work for that source branch, including the duplicate run for the same commit.
Different fork repositories and main remain separate. This uses GitHub's
[native concurrency behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).

PR workflows run `pull_request`, not `pull_request_target`, with read-only
repository access and no signing environment. PR-controlled workflow/build/test
code executes on a hosted VM that GitHub discards after the job. Repository
workflow/dispatch and protected-environment controls remain what keeps that code
away from the signing key.

The main release requires explicit dispatch and signs in its separate
`termux-fold-signing` job; public publication is a separate **default-false**
input. With publication off, the verified signed APK, checksum and provenance
are downloadable in an authenticated Actions artifact using the repository's
retention setting. Companion signing is **default-false**, dispatch-only, `fold/main`-only and
repository-bound, in the same protected environment. The key remains in its
existing protected GitHub secrets; no local backup is an input to these jobs.
Neither workflow installs an APK or grants Android permissions.

The unprivileged release job copies the reviewed snapshot helper from the
dispatched workflow commit before checking out the exact release tag. For tags
with a bootstrap lock, it restores matching archived inputs before a tagged-source
build and after clean; older tags without a lock retain their original build
path. The tagged source is not modified. Signed provenance records source and
workflow commits and the run that supplied the first unsigned APK.

PR and main checks run wrapper validation, full main tests, the native A-to-B
incremental rebuild, and one unsigned release build with packaged identity/SDK/ABI
and signature checks. The separate bootstrap workflow runs failure/negative/offline
checks and compares two bootstrap builds for changes to `tools/bootstrap/**` or
its workflow, and can also be dispatched manually. Ordinary app builds still
generate and validate the bootstrap from its locked inputs. Tests and the unsigned
release assembly and metadata share one Gradle invocation and its generated bootstrap. A
successful `fold/main` push uploads that verified unsigned APK. The release
workflow reuses it only if the successful main run has the exact annotated-tag
commit. If none is available, the workflow tests and builds the tagged source.
If the main APK differs from the clean release-job build, the workflow rebuilds
once more in the release job and requires those two builds to match byte for
byte. A clean, byte-identical release rebuild is always required before signing.
Reproducibility failures therefore block publication but can be found after
integration.
The companion retains two complete source chains, publication/resolution/source
preservation audits, byte-identical unsigned APKs, bounded same-run artifacts,
and independent optional signature/certificate/archive validation. API unit
sources remain absent; `NO-SOURCE` is not an additional passing suite. Git
commands reading the main workspace or a companion source checkout authorize
only that exact checkout for their own process, including source archiving.
This avoids the container's host-ownership error without changing global Git
configuration.

## Creating a signed update

The main workflow verifies and builds the exact annotated tag, not an unrelated
`fold/main` revision. A matching successful main run can supply the first
unsigned APK if the clean release-job build matches; otherwise the tag is built
and reproduced with a clean rebuild in the release job. Rebuilding an old tag cannot
include later commits; untagged main changes are not automatically signed or made
downloadable.

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

Pull requests and `fold/main` pushes run Fold checks on these runners; bootstrap
validation and the companion build run when their inputs change or on dispatch.
Both signing jobs run only on owner dispatch; a passing build job does not
qualify a signer. Results in the dependency/companion audits are evidence for
their stated revisions only.
Physical installation/backup/recovery and Fold acceptance remain separate
owner-controlled steps; never execute SDK x86_64 tools on ARM64 Termux to replace
that qualification.
