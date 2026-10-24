# Fold-compatible Termux:API companion

**Unmerged candidate:** this workflow is not a published companion, an installed app, or evidence that clipboard calls work on a device. The owner controls integration, hosted gates, any signing invocation, artifact download/publication, and installation separately.

## Two independent prerequisites

The error `Clipboard unavailable: install the Termux:API app and termux-api package` refers to **both**:

1. The native **`termux-api` client package** in the Termux prefix. Client defaults/bootstrap maintenance is separate from this companion workflow. APK updates do not populate an existing nonempty prefix. The current client assessment found version `0.60.0` already installed; no package installation was needed for this assignment.
2. The separately installed Android **Termux:API app**, package **`com.termux.api`**. The assessment found it absent for the primary Android user. Installing the CLI alone cannot supply this APK or its Android components.

Upstream's [signing warning](https://github.com/termux/termux-api/blob/6c8eaa223bcc387e6b28146d340acb20712aa3e7/README.md) matters here: the plugin declares `sharedUserId="com.termux"`, and its API broadcast receiver is not exported. It needs the **same existing Fold signing certificate** as the main app. Stock F-Droid and upstream GitHub APKs have different identities; they are **not** compatible substitutes for this Fold installation. Do not install a stock plugin, re-sign upstream's debuggable APK, create a new key, or uninstall Termux/plugins to switch identities. This workflow builds the unmodified **release** source and optionally signs that unsigned result with the protected existing Fold key.

This is a separate app with its own upstream version/code, not a new main Termux APK. It does not alter the main package/prefix, Fold version, main manifest, bootstrap, client defaults, or the owner's existing OSC 52 clipboard shim. Upstream's canonical clipboard clients invoke the native helper with `Clipboard` / `api_version=2`; the helper supplies the API broadcast and socket transport. A shell shim is not proof that this Android API route works.

## Audited source and retained recipe

Audit: 2026-10-24. [Latest stable release metadata](https://api.github.com/repos/termux/termux-api/releases/latest) identified [v0.53.0](https://github.com/termux/termux-api/releases/tag/v0.53.0), published 2025-09-01, not a draft/prerelease. Its release note fixes the BatteryStatusAPI null-current conversion/output; it does not claim a new clipboard protocol. The lightweight tag resolved to the following exact commit, whose GitHub metadata reports a verified signature. CI trusts the **reviewed commit/tree pins**, not a mutable tag or `latest` lookup.

| Input / packaged contract | Pinned value |
| --- | --- |
| Upstream | `termux/termux-api` (no new fork) |
| Commit | `6c8eaa223bcc387e6b28146d340acb20712aa3e7` |
| Tree | `f7262e96f32bb63c654396a1adc370c89395bec8` |
| Commit time / source epoch | `2025-09-01T23:16:14Z` / `1756768574` |
| Package / shared UID | `com.termux.api` / `com.termux` |
| Version name / code | `0.53.0` / `1002` (independent of Fold's main APK code) |
| SDK minimum / target / compile | **24 / 28 / 35**, from this upstream recipe, not inferred from the main app |
| ABI policy | **No native libraries**; no ARM64 filter is invented for this Java/resources companion |
| Recipe | AGP `8.7.3`, Gradle `8.9-all`, Java source/target `11`; hosted Java **21** |
| Compile tools | AGP 8.7's default build-tools `34.0.0`; platform `android-35` |
| Parser/signing tools | build-tools `37.0.0`, command-line tools `16111833` (23.0), same action/tool conventions as Fold CI |

[AGP 8.7 compatibility/release notes](https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes) specify Gradle 8.9, JDK minimum 17, build-tools default 34.0.0, and maximum supported API 35. Gradle 8.9 supports running on [Java 21](https://docs.gradle.org/8.9/userguide/compatibility.html). These older recipe pins are deliberate: the assignment is an **unmodified stable upstream release**, not an AGP/library modernization or a selective main-app dependency update. No compiler/SDK property override, Gradle init-script patch, debug build, or upstream test-key signing is used. Upstream already disables resource shrinking for reproducibility and minifies the release with its own ProGuard rules. Only the execution environment (UTC, UTF-8, isolated caches, no build/configuration cache, bounded worker count, recorded source epoch) is controlled; the epoch alone is not a reproducibility guarantee.

Distribution / wrapper provenance:

- Declared distribution: `https://services.gradle.org/distributions/gradle-8.9-all.zip`.
- Distribution SHA-256: `258e722ec21e955201e31447b0aed14201765a3bfbae296a46cf60b70e66db70`, independently checked against [Gradle's authoritative checksum](https://services.gradle.org/distributions/gradle-8.9-all.zip.sha256).
- The **actual upstream wrapper JAR** SHA-256 is `e996d452d2645e70c01c11143ca2d3742734a28da2bf61f25c82bdc288c9e637`, matching the older official [Gradle 6.6 wrapper checksum](https://services.gradle.org/distributions/gradle-6.6-wrapper.jar.sha256) (also reused by later 6.x versions). It is **not** the [8.9 wrapper JAR](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256), whose hash is `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.
- Upstream wrapper-properties SHA-256: `c4fcac9a6f994b73c3e402d75ad6bd07d51b66ee01e935663a3388b2c034bd37`. It names 8.9-all but supplies **no** distribution checksum. CI verifies the source/wrapper/properties first, downloads and hashes the declared distribution, then invokes its verified `bin/gradle` directly. The old wrapper is **not executed**, replaced, or edited.

The upstream [app recipe](https://github.com/termux/termux-api/blob/6c8eaa223bcc387e6b28146d340acb20712aa3e7/app/build.gradle) retains desugaring `1.1.5`, Material `1.12.0`, biometric **`1.2.0-alpha05`**, media `1.7.0`, preference `1.2.1`, the empty listenablefuture conflict artifact, and JitPack `com.termux.termux-app:termux-shared:7bceab88e2`. The alpha dependency is an inherited stable-release input, **not approval to adopt a new prerelease**. The JitPack revision and upstream's Maven graph are not a complete content-addressed dependency lock. CI performs fresh independent dependency resolutions; byte equality only establishes reproducibility for that run's resolved inputs, not an offline/hermetic or forever-reproducible supply chain. Dependency unavailability, incompatible metadata, missing tooling, or divergent APK bytes is a **blocking failure**, never permission to change upstream, substitute a binary, sign a debug APK, or weaken a gate. A recipe update requires separate review.

## Workflow and privilege boundary

[`.github/workflows/termux_api_companion.yml`](../.github/workflows/termux_api_companion.yml) runs on relevant `fold/main` pull requests and explicit `workflow_dispatch`. There is no push, schedule, release/tag trigger, `pull_request_target`, or default signing. Action commits reuse the audited Fold CI pins; Java/SDK installers and APK parsers come from those trusted actions, never from a build artifact.

### Unprivileged build

- Hosted Ubuntu 24.04 **x86_64**, read-only `contents` permission, no environment or signing secrets, no persisted checkout credentials, and no shared build cache.
- Checks out only the exact upstream commit; validates its tree, timestamp, clean state, wrapper and properties before source execution. Prepares two pristine source archives and two separate Gradle user homes.
- Runs upstream's release unit-test task and two release assemblies. This source contains **no unit-test sources**; a `NO-SOURCE` task is not an invented passing test suite. Full compilation/packaging and all APK gates remain hosted requirements.
- Requires exactly one correctly named APK per build, byte-for-byte `cmp` equality, package/version/SDK/shared-UID identity, non-debuggability, non-exported API receiver, zipalign, and no existing v1 certificate/JAR signature material or APK signing block. Both builds must pass the same trusted validator.
- Bounds APK/expanded ZIP/entry sizes; rejects duplicates, unsafe paths, symlinks/special entries, encryption/unsupported compression, native library paths/`.so` files/ELF payloads, noncanonical ZIP endings, and invalid CRCs. Native libraries contributed by termux-shared must be excluded as upstream specifies; a native payload is a blocker, not an excuse to invent an ABI claim.
- Uploads **only** `termux-api-unsigned.apk` and a strict public `manifest.json`, retained **one day**. No debug APK, key, cache, downloaded executable, or generated script is a signing input.

### Optional signing

An owner may deliberately select **`sign_companion: true`** only after integration/review, on **`refs/heads/fold/main`** in `fitchmultz/termux-app`. The default is **false**. The fresh signer is scoped to **`termux-fold-signing`**, with read-only `actions` permission and the existing protected secrets `TERMUX_FOLD_KEYSTORE_BASE64`, `TERMUX_FOLD_KEYSTORE_PASSWORD`, `TERMUX_FOLD_KEYSTORE_SHA256` / alias `termux-fold-release`. It never checks out a repository or runs Gradle, restores caches, sources manifest text, or executes any upstream/artifact-provided code.

Before secrets are exposed to a step, the signer checks the artifact ID/name/size and workflow-run/head identity via the repository-scoped GitHub API, downloads only the build job's immutable artifact from the **same run/attempt**, and independently checks the exact two filenames, file types, size limits, strict manifest schema/values/no extra or duplicate keys, workflow/source pins, APK hash, and actual APK metadata/archive/absence of signing. The reusable Python validator is **inline trusted workflow code**, not a downloaded script. Manifest data is parsed as JSON, never shell-sourced or interpolated into executable code.

The secret-bearing step checks the keystore SHA and the alias's public DER certificate hash **before signing**, uses password files and explicit v1/v2/v3 enabled / v4 disabled, unsets credential environment values, and cleans the private files on exit/signals. An `always()` cleanup step also runs on ordinary job failure/cancellation; hosted runner disposal is the last boundary for hard termination. No credential values are echoed. A subsequent secret-free step independently rechecks the signed package/shared UID/version/SDK/non-debuggable/native/archive/zipalign contract, one signer, v1+v2+v3 validity, v4 absence, and every reported signing-certificate SHA-256. Verification alone uses an API-23 floor to exercise v1; the APK minimum remains 24.

Expected **existing Fold certificate**:

```text
22:1F:36:CC:A6:E3:2E:E1:66:6B:B2:10:04:66:54:B2:69:00:CD:9E:A9:8C:21:55:D0:CB:E6:61:02:06:04:37
```

Successful signing emits one **three-day artifact** containing exactly:

- `Termux-API-0.53.0-Fold.apk`
- `SHA256SUMS`
- `PROVENANCE.json` (public source/tree/version/build/tool/workflow/unsigned+signed hashes and certificate/scheme facts)

There is **no** GitHub Release/tag creation, publication/update, main-APK change, installation, adb/pairing, permission auto-grant, or device operation. A signed artifact is not installed, published, or physically validated merely because the job succeeded. The owner must download/review it before retention expires if a later authorized distribution is wanted; GPLv3 source/license obligations accompany any eventual distribution.

Repository/environment protection remains essential: restrict who can modify the workflow, source pins and validators on `fold/main`, and who can dispatch/use `termux-fold-signing`. A boolean and branch condition do not defend against a malicious authorized workflow editor. APK metadata/hash checks are structural and integrity gates, **not** proof that source/dependencies are harmless or an independent reconstruction of their derivation. Review upstream and dependency provenance before accepting a pin change. Update commit/tree/epoch, actual wrapper/distribution hashes, recipe/tools, validator and manifest contract together in a reviewed PR; rerun all hosted gates before any opt-in signing. Never resolve a failure by turning off a validator.

## Owner-controlled installation and physical validation

**Installation hold is active for both the main and companion APKs while protected peer work runs.** Shared-UID/package changes can interrupt Termux and sibling processes even when only installing a new plugin. This workflow does not lift that hold. Checkpoint work and follow the main [installation boundary](../FOLD_PROFILE.md#installation-boundary), signer checks, backups and recovery readiness before the owner separately authorizes any Android package change. Never uninstall to work around a signing conflict.

After the hold is explicitly lifted, the owner can inspect the separately downloaded APK/checksum/provenance and use Android's ordinary installer with explicit confirmation. The target-28 app may show an old-app warning on modern Android. Confirm package/version and Fold certificate, not just a filename. Do not assume that changing or reinstalling the main APK is required to install this companion, or that a main APK update installs the companion automatically.

Termux:API exposes much more than clipboard functions and shares the Fold UID trust boundary. Its upstream manifest requests optional sensitive Android capabilities (for example contacts, camera, microphone, phone/SMS, location, storage and notification access). Ordinary Android runtime/special-access permission controls still apply; declaring a permission does not automatically grant it, and some protected permissions are unavailable to ordinary apps. Review shared-UID privacy implications and enable only the APIs/access actually needed. Do **not** auto-grant all permissions or turn on unrelated services to make clipboard access work.

Clipboard functionality remains **unverified** until the owner performs a safe physical probe after authorized installation: deliberately use non-sensitive, disposable content, account for foreground/background restrictions, confirm both canonical set/get routes and expected errors without logging existing clipboard contents, and restore user state only with consent. Do not read, print, upload, or overwrite the user's real clipboard as part of CI or this implementation. Record actual results, including permission prompts and modern-Android limitations, rather than inferring success from an APK build or the presence of the CLI.

## Verification / remaining gates

Local verification passed actionlint, `bash -n` for every shell block, YAML/JSON/embedded-Python parsing, source/hash/metadata-reference checks and local documentation links. Java 21 configuration (`:app:tasks --all`) succeeded with an isolated temporary Gradle home. **Observed source-build blocker (2026-10-24):** the release dependency report and dependency insight could not resolve `com.termux.termux-app:termux-shared:7bceab88e2`; its JitPack POM returned **HTTP 404**. Gradle's report tasks exit successfully even with this `FAILED` dependency: that exit status is **not** a successful dependency/build gate. Hosted assembly must fail closed if the pinned artifact remains unavailable; do not patch the coordinate, seed a substitute cache, or sign anything to bypass it. ShellCheck 0.11.0's verified Linux ARM64 binary could not start under Android's syscall restrictions (SIGSYS); run ShellCheck on a compatible host before promotion. No package/tool installation into the live prefix was attempted.

On ARM64 Termux, use Java 21 and an isolated temporary Gradle home for configuration/dependency reports only; never run the SDK's x86_64 aapt/zipalign binaries or attempt a local APK build. Missing SDK platforms/dependencies and repository-resolution errors must be reported without changing the live SDK/prefix.

Before promotion, the parent must run the read-only PR workflow on hosted x86_64 and inspect both clean builds, available test task output, APK metadata/archive/no-cert/zipalign gates, manifest and reproducibility. No hosted run, signing invocation or physical probe was performed by this implementation assignment. A successful build-only dispatch does **not** exercise the opt-in signer; review its privilege boundary before a separately authorized main-branch signing dispatch. Keep the install hold and publication/installation decisions independent of either job's success.
