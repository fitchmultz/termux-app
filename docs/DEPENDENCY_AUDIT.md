# Dependency and tooling audit — 2026-10-24

Candidate: `fold/maintenance-refresh`, based on `fold/main` at `fc0ccf29`. This is a selective maintenance update, not an upstream sync or a release/version bump. The tables cover every external direct Gradle coordinate, the wrapper, SDK/NDK/JDK, bootstrap, and every action in both retained workflows. Project-to-project dependencies do not have independent releases.

## Changes

| Input | Before → candidate | Authoritative evidence and compatibility |
| --- | --- | --- |
| Gradle | 9.8.1 → **9.8.2** | [Release notes](https://docs.gradle.org/9.8.2/release-notes.html): fixes a false virtual-platform graph-stability warning. [Current stable metadata](https://services.gradle.org/versions/current), [distribution SHA-256](https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256), and [wrapper SHA-256](https://services.gradle.org/distributions/gradle-9.8.2-wrapper.jar.sha256) agree. AGP 9.4 requires Gradle ≥9.6; Java 21 remains supported. Ran the canonical wrapper task twice; scripts/JAR remain byte-identical. |
| Compile SDK | 37.0 r2 → **37.2 r1** | Google's [SDK repository metadata](https://dl.google.com/android/repository/repository2-3.xml) lists `platforms;android-37.2` on stable channel 0 without a preview revision/codename. Uses AGP's public [CompileSdkSpec release/minor API DSL](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CompileSdkSpec) consistently across all four modules. Debug/release AAR-metadata checks pass with this platform; this does **not** raise target 28 or minimum 24. |
| SDK command-line tools | implicit 22.0 / 15859902 → explicit **23.0 / 16111833** | Same official SDK metadata: stable channel, no preview. [Official tool notes](https://developer.android.com/tools/releases/cmdline-tools) have no detailed 23.0 changelog. The pinned setup-android [input](https://github.com/android-actions/setup-android/blob/v4.0.4/action.yml) accepts a build ID; its [implementation](https://github.com/android-actions/setup-android/blob/v4.0.4/src/main.ts) supports IDs newer than its default. Explicit provisioning remains in both unprivileged build jobs and the fresh signer job. |
| Java compilation | source/bytecode 8 → **17**; explicit **21** compiler/test toolchain | [Android JDK guidance](https://developer.android.com/build/jdks) recommends explicit toolchains and matched source/target levels, with Java 17 examples. Modern language/bytecode is processed by AGP/D8; it is not Android target SDK or permission to call unavailable Java/platform APIs. Existing app/shared core-library desugaring 2.1.5 and API 24 regressions remain; no new APIs or preview language features are introduced. This removes obsolete Java 8 compilation warnings, not by suppressing them. |
| `actions/setup-java` | v6.0.1 → **v6.1.0** | [Notes](https://github.com/actions/setup-java/releases/tag/v6.1.0): Temurin patch-version resolution, verification-agent cleanup, and dependency fixes; no Node runtime change (still Node 24). Verified tag commit `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` and preserved the Temurin Java 21 input. `check-latest: true` selects current stable 21.x security patches rather than an older matching runner-cache entry. |

Gradle 9.8.2 distribution SHA-256: `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`.
Unchanged wrapper JAR SHA-256: `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98` (the signing-workflow assertion remains valid).

## Retained direct libraries: latest stable already in source

Compared **all versions**, not just Maven's `<latest>`/`<release>` fields: those fields can name prereleases. Google coordinates were checked against `https://dl.google.com/dl/android/maven2/<group/path>/<artifact>/maven-metadata.xml`; Central coordinates against `https://repo.maven.apache.org/maven2/<group/path>/<artifact>/maven-metadata.xml`. These are publication metadata, not a third-party version catalog. The release-note links below were fetched as well.

| Direct coordinate | Retained stable | Notes / compatibility decision |
| --- | --- | --- |
| `androidx.annotation:annotation` | 1.11.0 | [Notes](https://developer.android.com/jetpack/androidx/releases/annotation#1.11.0): WorkerThread KMP and DoNotInline improvements; no newer stable. Java consumers need no KMP migration. |
| `androidx.core:core` | 1.19.1 | [Notes](https://developer.android.com/jetpack/androidx/releases/core#1.19.1): lifecycle dependency/lint compatibility fix. Core now includes KTX; no redundant KTX artifact is added. |
| `androidx.appcompat:appcompat` | 1.8.0 | [Notes](https://developer.android.com/jetpack/androidx/releases/appcompat#1.8.0): min SDK 23, JSpecify, view-tree/configuration fixes. Existing minimum 24 and AGP 9.4 satisfy requirements. |
| `androidx.drawerlayout:drawerlayout` | 1.2.0 | [Notes](https://developer.android.com/jetpack/androidx/releases/drawerlayout#1.2.0): predictive-back integration requires opt-in; retain target-28 drawer contract. |
| `androidx.preference:preference` | 1.2.1 | [Notes](https://developer.android.com/jetpack/androidx/releases/preference#1.2.1): back/fragment argument fixes; no newer publication. |
| `androidx.viewpager:viewpager` | 1.1.0 | [Notes](https://developer.android.com/jetpack/androidx/releases/viewpager#1.1.0): stretch overscroll and nullability. ViewPager2 is a different widget/API, not a version bump; no unrelated toolbar/pager rewrite. |
| `androidx.window:window` | 1.5.1 | [Notes](https://developer.android.com/jetpack/androidx/releases/window#1.5.1): device ClassCastException fix. 1.6.0-alpha05 is **not** stable; no alpha adoption. |
| `com.google.android.material:material` | 1.14.0 | [Notes](https://github.com/material-components/material-components-android/releases/tag/1.14.0): min SDK 23, Expressive styles, keyboard/accessibility improvements. Requirements fit; no broad theme/UX migration in dependency maintenance. |
| `com.google.guava:guava` | 33.7.2-jre | [Notes](https://github.com/google/guava/releases/tag/v33.7.2): includes deserialization allocation security fix. Retain `api` because public schemas expose Guava maps, and JRE flavor because sharing uses MoreFiles/NIO with existing desugaring. |
| `commons-io:commons-io` | 2.22.0 | [Notes](https://commons.apache.org/proper/commons-io/changes.html): Java 8 baseline; no newer stable, existing desugaring retained. |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.2 | Central metadata and [maintainer compatibility guidance](https://github.com/LSPosed/AndroidHiddenApiBypass): Android 17 supported. No GitHub Release/changelog is published. Keep reliable HiddenApiBypass rather than switching to the documented less-reliable LSPass or a dynamic `+` version. |
| `io.noties.markwon:core` | 4.6.2 | [Notes](https://github.com/noties/Markwon/releases/tag/v4.6.2), Central metadata: latest stable. A renderer replacement is not an upgrade to this artifact. |
| `io.noties.markwon:ext-strikethrough` | 4.6.2 | Checked its own Central metadata; matches Markwon core. |
| `io.noties.markwon:linkify` | 4.6.2 | Checked its own Central metadata; matches Markwon core. |
| `io.noties.markwon:recycler` | 4.6.2 | Checked its own Central metadata; matches Markwon core. |
| `com.termux:termux-am-library` | v2.0.0 | [Maintainer release](https://github.com/termux/termux-am-library/releases/tag/v2.0.0), verified tag `d979ee308d52c59c6265ed9d9e254c97702ee4fb`, and successful JitPack resolution. Release body is empty; no newer release exists. |
| `junit:junit` | 4.13.2 | [Release](https://github.com/junit-team/junit4/releases/tag/r4.13.2): latest of this coordinate. Jupiter/JUnit Platform are different coordinates/runners, not drop-in bumps for the current Robolectric/JUnit 4 suite. |
| `org.robolectric:robolectric` | 4.17 | [Notes](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.17): SDK 37 support, removed AndroidVersions API, JDK ≥17 flag guidance. Existing tests already use Android 7/17 and retain the required Java 21 FileDescriptor export. |
| `com.android.tools:desugar_jdk_libs` | 2.1.5 | [Changelog](https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md): Stream.toList support, AGP ≥8.0 for 2.1.x. Latest stable, required Java/NIO compatibility retained. |

No library source or test files were changed, and no transitive versions were blindly forced. The resolved release and test graphs contain no failed coordinates.

## Retained tooling and actions

| Input | Decision / authoritative notes |
| --- | --- |
| AGP 9.4.1 | Latest stable in [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml). [9.4 notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes): API 37, Gradle ≥9.6, JDK ≥17; public DSL retained. 9.5.0-rc01 and 9.6.0-alpha01 are previews, not updates to adopt. |
| Build-tools 37.0.0 | Latest stable in SDK repository metadata; [tool notes](https://developer.android.com/tools/releases/build-tools). Existing per-scheme certificate parsing and verifier-only v1 floor remain unchanged. |
| NDK 30.0.16248370 (r30) | Latest non-preview in SDK repository metadata. [NDK history](https://developer.android.com/ndk/downloads/revision_history) lags repository metadata; do not infer an r31 release. Native ABI/minimum and 16 KiB ZIP-alignment checks remain. |
| Java 21 / Temurin | Supported LTS used by both local configuration and the established Robolectric bridge, not an assertion that 21 is the newest Java major. [Current stable 21 vendor release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9): 21.0.13+9. Newer 25 LTS/27 major releases do not require a JVM migration for this update; retaining 21 avoids changing the tested internal-API bridge. CI now resolves latest 21.x explicitly. |
| `actions/checkout` v7.0.1 | [Notes](https://github.com/actions/checkout/releases/tag/v7.0.1), verified commit `3d3c42e5aac5ba805825da76410c181273ba90b1`; already latest. Keep `persist-credentials: false`. |
| `gradle/actions/wrapper-validation` v6.4.0 | [Notes](https://github.com/gradle/actions/releases/tag/v6.4.0), verified annotated tag peels to `3f5f9adaf7d9fecd50b5935e54106014257a94e6`; already latest. No dependency-submission/write-permission workflow is introduced. |
| `android-actions/setup-android` v4.0.4 | [Notes](https://github.com/android-actions/setup-android/releases/tag/v4.0.4), verified commit `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd`; already latest action. Only its SDK inputs change above. |
| `actions/upload-artifact` v7.0.2 | [Notes](https://github.com/actions/upload-artifact/releases/tag/v7.0.2), verified commit `cf430e030ddbb5b0abf93d22962f4752f3646cd9`; current retry fixes retained. Short retention/unsigned-only handoff unchanged. |
| `actions/download-artifact` v8.0.2 | [Notes](https://github.com/actions/download-artifact/releases/tag/v8.0.2), verified commit `9000827ccba6bdab643e8b6fd33ac0654aef8333`; current retry fixes retained. Same-run signer input contract unchanged. |
| Hosted `ubuntu-latest` and utilities | Retain GitHub's stable managed runner alias, currently [Ubuntu 24.04](https://github.com/actions/runner-images/blob/main/README.md), although a separate 26.04 image exists. Bash/Git/ZIP/hash/GitHub CLI utilities come from that image, not independently version-pinned project dependencies. No runner migration or new build tool is required. |
| Platform-tools | setup-android's `packages` input is explicit and does not request platform-tools; hosted builds do not require adb. SDK metadata lists 37.0.1, but installing an unused tool adds no coverage. |

## Bootstrap: audited, reserved for parallel default-tools integration

Source deliberately remains at `2026.10.11-r1+apt.android-7` in this PR because the owner/default-tools writer is replacing the bootstrap input with a locked offline package closure. Do not race that writer by changing its archive/helper here.

The [newest stable upstream release](https://github.com/termux/termux-packages/releases/tag/bootstrap-2026.10.18-r1%2Bapt.android-7) is **2026.10.18-r1+apt.android-7**. Its release body contains no package notes. Downloaded both ARM64 archives, verified the old source hash and the new GitHub asset SHA-256, checked ZIP CRCs, compared dpkg status, and inspected ELF headers without executing bundled programs:

- New archive: `bootstrap-aarch64.zip`, 33,071,189 bytes, SHA-256 `7b170ff5ef0befe501d01febc61382f3c6e34456521853cff9ba0b277aa52fe9`.
- Both archives contain 83 installed packages and 338 ELF files; every ELF is AArch64. No packages added/removed.
- Package-version delta: coreutils 9.11-1 → 9.12; curl/libcurl 8.22.0 → 8.23.0; libmpfr 4.2.1-1 → 4.2.2; command-not-found 3.5.0-14 → 3.5.0-15.
- The Android 7 variant, `com.termux` prefix, and no-APK-install boundary remain. Existing prefixes still require user-run `pkg update && pkg upgrade`; APK maintenance never upgrades them automatically.

## Verification and disclosed limits

Local ARM64 Termux, Java 21.0.13, with existing SDK tools and an isolated official 37.2 platform (not a shared SDK replacement):

- Canonical `:wrapper --gradle-version=9.8.2 --gradle-distribution-sha256-sum=…`, then `:wrapper`: successful; official wrapper hash matches and no wrapper-script/JAR churn.
- `help --warning-mode all` and `:app:foldReleaseMetadata`: successful; version `0.119.0-fold.9` / `2026101309`, label, package variant, ARM64 unchanged.
- `:app:dependencies --configuration releaseRuntimeClasspath`; `:app:dependencies --configuration debugUnitTestRuntimeClasspath`; each library module's `releaseRuntimeClasspath` report; root `buildEnvironment`: successful, no failed dependency coordinates.
- `:app:checkDebugAarMetadata :app:checkReleaseAarMetadata`: successful against SDK 37.2; no x86_64 SDK native tools executed.
- `:terminal-emulator:testDebugUnitTest`: **154 tests, 0 failures/errors/skips** after the Java 17 change; inspected compiled class major version 61. The obsolete Java 8 source/target warnings disappear; the existing TerminalSession deprecated-API notice remains.
- Evaluated all four actual Android/Java extensions: compile 37.2, min 24, source/bytecode 17, toolchain 21; app target 28, namespace/version/ARM64 and unsigned release configuration unchanged.
- `actionlint .github/workflows/run_tests.yml .github/workflows/signed_release.yml` and `git diff --check`: successful.
- Full tests/debug APK/two unsigned release builds and packaged metadata/ABI/signature/reproducibility checks belong to **Fold checks** on GitHub. Exact run/head results are recorded in the PR, not inferred from local reports. The manual signing/publishing workflow is **not dispatched** by this assignment; signer changes are limited to the audited Java setup action and command-line-tool input.

Known pre-existing warning: AGP 9.4.1 calls deprecated `Configuration.setVisible(boolean)` inside `BasePlugin.createAndroidJdkImageConfiguration` (confirmed with Gradle's deprecation trace). This is plugin-owned, scheduled for removal in Gradle 11; no suppression, private-API workaround, or prerelease AGP upgrade is added. Termux also reports unavailable Gradle native environment integration; configuration/report/metadata commands still exit successfully. Neither warning is hidden as a clean/no-warning build claim.
