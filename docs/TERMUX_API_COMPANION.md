# Fold-compatible Termux:API companion

This workflow builds a Fold-compatible companion; integrating the workflow does not publish or install that app or establish working clipboard calls. Runner qualification, any signing invocation, artifact download/publication, installation and physical validation are separate owner-controlled steps.

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
| Recipe | AGP `8.7.3`, Gradle `8.9-all`, Java source/target `11`; Java **21 x64** |
| Compile tools | AGP 8.7's default build-tools `34.0.0`; platform `android-35` |
| Parser/signing tools | build-tools `37.0.0`, command-line tools `16111833` (23.0), same action/tool conventions as Fold CI |

[AGP 8.7 compatibility/release notes](https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes) specify Gradle 8.9, JDK minimum 17, build-tools default 34.0.0, and maximum supported API 35. Gradle 8.9 supports running on [Java 21](https://docs.gradle.org/8.9/userguide/compatibility.html). Upstream already disables resource shrinking for reproducibility and minifies the release with its own ProGuard rules.

The upstream [API app recipe](https://github.com/termux/termux-api/blob/6c8eaa223bcc387e6b28146d340acb20712aa3e7/app/build.gradle) retains desugaring `1.1.5`, Material `1.12.0`, biometric **`1.2.0-alpha05`**, media `1.7.0`, preference `1.2.1`, the empty listenablefuture conflict artifact, and `com.termux.termux-app:termux-shared:7bceab88e2`. The declared coordinate is unchanged. The alpha dependency is an inherited stable-release input, **not approval to adopt a new prerelease**.

### Exact-source dependency route, not an opaque AAR substitute

Local dependency insight reported the shared module **FAILED** despite Gradle exiting 0. The parent's bounded [hosted egress probe](https://github.com/fitchmultz/termux-app/actions/runs/40125975110), from workflow commit `962a90f50fa6e4b613fc9e5edbb4724d83c1e8b1`, independently confirmed both custom-domain and canonical GitHub shared POM/log URLs returned **404**, both status APIs returned historical `ok` / `Not found`, and the AM control POM returned 200. This is not a phone-only egress failure; it does not prove permanent deletion. CI does not retry that missing binary, use a mirror/cache AAR, or change to another source version.

[Termux's library documentation](https://github.com/termux/termux-app/wiki/Termux-Libraries) documents local `components.release` Maven publication and the custom-domain group mapping. The [F-Droid v0.53.0 recipe](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.termux.api.yml) and its [successful 2025-09-05 build log](https://f-droid.org/repo/com.termux.api_1002.log.gz) use ordinary API release assembly with remote dependencies, not a source-dependency recipe we can reuse. The approved route here builds the exact dependency sources itself:

| Producer source | Exact source identity | Retained recipe |
| --- | --- | --- |
| [`termux/termux-app`](https://github.com/termux/termux-app/tree/7bceab88e2272f961d1b94ef736f1a9e20173247), resolving `7bceab88e2` | Commit `7bceab88e2272f961d1b94ef736f1a9e20173247`; tree `e982c267beddd24e389f6a4cee378dfc09f5f605`; epoch `1750867852` | AGP **4.2.2**, Gradle **7.2-all**, JDK **11**, Java source/target 8, compile SDK **30**, min/target **21/28**, build-tools **30.0.2** |
| [`termux/termux-am-library`](https://github.com/termux/termux-am-library/tree/d979ee308d52c59c6265ed9d9e254c97702ee4fb), resolving `v2.0.0` | Commit `d979ee308d52c59c6265ed9d9e254c97702ee4fb`; tree `f986157b9b588f527825c924623811149cb03ee8`; epoch `1650918843` | AGP **7.0.3**, Gradle **7.2-all**, JDK **11**, Java source/target 8, compile SDK **30**, min/target **21/28**, build-tools **30.0.2**; no native build |

The shared producer uses NDK **21.1.6352462**, explicitly selected by its pinned `jitpack.yml`; the root fallback **22.1.7171670** is not silently substituted. Its original native ABIs remain intact: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. `terminal-emulator` builds `libtermux.so`; `termux-shared` builds `liblocal-socket.so`. The final API APK excludes both through its existing packaging rules, not by disabling producer native tasks. Google SDK metadata still offers platform 30 revision 3, build-tools 30.0.2 and this side-by-side NDK.

These historical versions are **reconstruction inputs for the approved exact sources**, not new dependency-maintenance baselines. [AGP 4.2](https://developer.android.com/build/releases/past-releases/agp-4-2-0-release-notes) and [AGP 7.0](https://developer.android.com/build/releases/past-releases/agp-7-0-0-release-notes) document the retained tool requirements. [Gradle 7.2](https://docs.gradle.org/7.2/userguide/compatibility.html) cannot run on Java 21: SDK installation uses Java 21, producer execution uses Java 11, and API consumption returns to Java 21. Pinned setup actions resolve patched Temurin 11.x/21.x; the manifest records actual Java versions and selected JDK file hashes. No JitPack setup hooks run.

Distribution / wrapper provenance:

- API Gradle **8.9-all** SHA-256: `258e722ec21e955201e31447b0aed14201765a3bfbae296a46cf60b70e66db70` ([official checksum](https://services.gradle.org/distributions/gradle-8.9-all.zip.sha256)).
- Producer Gradle **7.2-all** SHA-256: `a8da5b02437a60819cad23e10fc7e9cf32bcb57029d9cb277e26eeff76ce014b` ([official checksum](https://services.gradle.org/distributions/gradle-7.2-all.zip.sha256)).
- All three source repositories actually contain wrapper JAR SHA-256 `e996d452d2645e70c01c11143ca2d3742734a28da2bf61f25c82bdc288c9e637`, matching the older official [Gradle 6.6 wrapper](https://services.gradle.org/distributions/gradle-6.6-wrapper.jar.sha256), **not** a 7.2 or 8.9 wrapper.
- API wrapper-properties SHA-256: `c4fcac9a6f994b73c3e402d75ad6bd07d51b66ee01e935663a3388b2c034bd37`; both producer properties: `cc162bed9654746905008154a8e69de28b28316f1b6c146bbfd1fcd13f554cfa`.
- Properties declare the distributions but lack checksum enforcement. CI validates source/wrapper/properties before source execution, independently hashes both downloaded distributions, then invokes their verified `bin/gradle` directly. No source wrapper is executed, replaced or edited.

### Deliberately narrow recipe overlays

**Production sources and every tracked upstream build file remain byte-identical; the evaluated build-resolution/publication recipe is overlaid, not claimed unmodified.** Three small trusted inline workflow values define the overlay:

1. `LIBRARY_SETTINGS` includes only `termux-shared`, `terminal-view`, and `terminal-emulator` for the shared producer. Its main `:app` is not even configured; no main APK/bootstrap task or test-key signing runs. AM's settings already contain only its library.
2. `PUBLISH_INIT` uses the existing `release` publications from `components.release`, changes only publication identities and the file-repository destination, and asserts the producer project set. It publishes AM as **`com.termux:termux-am-library:v2.0.0`** and all three shared modules as **`com.termux.termux-app:<module>:7bceab88e2`**. It does not synthesize an AAR, POM or module descriptor.
3. `LOCAL_REPOSITORY_INIT` uses an exclusive round-local Maven repository for the named source modules, without remote fallback or user `mavenLocal()`. A pre-assembly resolution task checks all external Termux component identities, then uses a public, non-lenient artifact view selecting only those groups' raw `aar` artifacts—not project files or AGP intermediates. It does not change production configuration attributes. Exact GAVs, raw type, canonical unclassified AAR basenames, complete artifact coverage and hashes against the generated local publications are mandatory; the separate identity check rejects unexpected Termux GAVs even if the AAR view would omit their artifacts. Shared production resolves exact AM locally; API resolves all four locally.

Gradle-generated POM **and** `.module` metadata must retain the exact direct graph and scopes: shared's implementation dependencies include `terminal-view` and AM; view exposes emulator through `api`; annotation dependencies retain their original implementation scopes. Source manifests/resources and the packager's actual consumer-rule semantics remain intact. In particular, ordinary library `proguardFiles` are **not** newly attached as consumer rules; AM's declared consumer file is empty. Metadata checks verify identities, graph/scopes, API/runtime variants, artifact filenames/sizes/digests, complete AARs and the producer native-library/ABI sets.

The overlays do not change compiler/SDK settings, dependency versions, production Java/C/C++/resources/manifests, API version/identity, or signing behavior. No composite mixes the incompatible producer/consumer Gradle baselines. Any source/generator/publication incompatibility is a blocking failure requiring review, not permission to patch production or drop metadata.

For **each of two rounds**, CI uses fresh source archives, three distinct Gradle homes and an empty Maven repository, publishes AM plus all three shared modules, then builds the API consumer. It checks every archived source/build file and mode after execution, hashes the actual overlays and records per-round archive/AGP/AAR/POM/module inputs plus selected SDK tool/JDK files. Only UTC, UTF-8, isolated homes, disabled build/configuration caches, bounded workers and recorded source epochs control execution. Epochs alone do not ensure determinism. Intermediate AAR hashes may differ; they are recorded truthfully and are not claimed equal to inaccessible historical JitPack binaries. **The final unsigned APKs must be byte-identical.**

The remaining Google/Maven graph is not fully content-addressed or offline/hermetic. The input manifest is bounded public provenance of this run, not an exhaustive toolchain attestation or proof of harmless source. Missing dependencies, incorrect generated metadata, source changes, native omissions, or unequal final APK bytes fail closed. A source/tool/recipe update requires separate review.

## Workflow and privilege boundary

[`.github/workflows/termux_api_companion.yml`](../.github/workflows/termux_api_companion.yml) runs on relevant `fold/main` pull requests and explicit `workflow_dispatch`. There is no push, schedule, release/tag trigger, `pull_request_target`, or default signing. Action commits reuse the audited Fold CI pins; Java/SDK installers and APK parsers come from those trusted actions, never from a build artifact.

Both SDK setup steps set `accept-android-sdk-licenses: false`: CLT 23 removed the legacy `sdkmanager --licenses` handshake, whose immediate no-op exit can cause the action's stdin write to fail with EPIPE. This skips only that obsolete command, **not** actual package-license agreement. Package installation, the new Android CLI's license handling and fatal installation errors remain unchanged.

### Unprivileged build

- A self-hosted **Linux ARM64** runner on the owner's persistent private M1 VM executes a digest-pinned Ubuntu 24.04 **linux/amd64** job container through Rosetta. Java 11/21 are explicitly x64; the action runtime remains ARM64. Read-only `contents` permission, no environment or signing secrets, no persisted checkout credentials, and no shared build cache remain. [Runner policy](CI_RUNNER.md) describes the required read-only runtime libraries and offline queue behavior.
- Checks out only the three exact pinned upstream sources; validates their trees, timestamps, clean states, wrappers and properties before execution. Identity and archive Git commands authorize only the exact resolved checkout for that process, without a wildcard or global configuration change. Prepares two independent full producer→consumer chains as described above.
- Runs upstream's release unit-test task and two release assemblies. This source contains **no unit-test sources**; a `NO-SOURCE` task is not an invented passing test suite. Full compilation/packaging and all APK gates remain runner requirements.
- Requires exactly one correctly named APK per build, byte-for-byte `cmp` equality, package/version/SDK/shared-UID identity, non-debuggability, non-exported API receiver, zipalign, and no existing v1 certificate/JAR signature material or APK signing block. Both builds must pass the same trusted validator.
- Bounds APK/expanded ZIP/entry sizes; rejects duplicates, unsafe paths, symlinks/special entries, encryption/unsupported compression, native library paths/`.so` files/ELF payloads, noncanonical ZIP endings, and invalid CRCs. Native libraries contributed by termux-shared must be excluded as upstream specifies; a native payload is a blocker, not an excuse to invent an ABI claim.
- Uploads **only** `termux-api-unsigned.apk` and a strict **16 KiB-bounded** public `manifest.json`, retained **one day**. The compact schema 3 records the ARM64 registered runner separately from the pinned amd64 container/image and x64 Java execution choice, both rounds' input sizes/digests, fixed source/tool/coordinate pins and reviewed overlay hashes. No AAR, debug APK, key, cache, downloaded executable or generated script is a signing input.

### Optional signing

An owner may deliberately select **`sign_companion: true`** only after integration/review, on **`refs/heads/fold/main`** in `fitchmultz/termux-app`. The default is **false**. The separate signing job is scoped to **`termux-fold-signing`**, with read-only `actions` permission and the existing protected secrets `TERMUX_FOLD_KEYSTORE_BASE64`, `TERMUX_FOLD_KEYSTORE_PASSWORD`, `TERMUX_FOLD_KEYSTORE_SHA256` / alias `termux-fold-release`. It never checks out a repository or runs Gradle, restores caches, sources manifest text, or executes any upstream/artifact-provided code.

Before secrets are exposed to a step, the signer checks the artifact ID/name/size and workflow-run/head identity via the repository-scoped GitHub API, downloads only the build job's immutable artifact from the **same run/attempt**, and independently checks the exact two filenames, file types, size limits, strict manifest schema/values/no extra or duplicate keys, workflow/source pins, APK hash, and actual APK metadata/archive/absence of signing. The canonical APK validator remains **inline trusted workflow code**, not a downloaded script. The signer narrowly validates the new public source-build record names/counts/digest formats/size bounds and exact reviewed overlay hashes; it also matches its installed verification-tool files against the recorded hashes. It does **not** rebuild or independently attest producer AARs from those claims. Manifest data is parsed as JSON, never shell-sourced or interpolated into executable code.

The secret-bearing step checks the keystore SHA and the alias's public DER certificate hash **before signing**, uses password files and explicit v1/v2/v3 enabled / v4 disabled, unsets credential environment values, and cleans the private files on exit/signals. An `always()` cleanup step also runs on ordinary job failure/cancellation. Each signing job creates its own temporary SDK/input/output root, but the VM and runner storage persist; hard termination can leave files requiring runner cleanup, not fresh-machine disposal. No credential values are echoed. A subsequent secret-free step independently rechecks the signed package/shared UID/version/SDK/non-debuggable/native/archive/zipalign contract, one signer, v1+v2+v3 validity, v4 absence, and every reported signing-certificate SHA-256. Verification alone uses an API-23 floor to exercise v1; the APK minimum remains 24.

Expected **existing Fold certificate**:

```text
22:1F:36:CC:A6:E3:2E:E1:66:6B:B2:10:04:66:54:B2:69:00:CD:9E:A9:8C:21:55:D0:CB:E6:61:02:06:04:37
```

Successful signing emits one **three-day artifact** containing exactly:

- `Termux-API-0.53.0-Fold.apk`
- `SHA256SUMS`
- `PROVENANCE.json` (public source/tree/version/build/tool/workflow/unsigned+signed hashes and certificate/scheme facts)

There is **no** GitHub Release/tag creation, publication/update, main-APK change, installation, adb/pairing, permission auto-grant, or device operation. A signed artifact is not installed, published, or physically validated merely because the job succeeded. The owner must download/review it before retention expires if a later authorized distribution is wanted; GPLv3 source/license obligations accompany any eventual distribution.

Repository/environment protection remains essential: restrict who can modify the workflow, source pins and validators on `fold/main`, and who can dispatch/use `termux-fold-signing`. A boolean and branch condition do not defend against a malicious authorized workflow editor. APK metadata/hash checks are structural and integrity gates, **not** proof that source/dependencies are harmless or an independent reconstruction of their derivation. Review upstream and dependency provenance before accepting a pin change. Update commit/tree/epoch, actual wrapper/distribution hashes, recipe/tools, validator and manifest contract together in a reviewed PR; rerun all runner gates before any opt-in signing. Never resolve a failure by turning off a validator.

## Owner-controlled installation and physical validation

**Installation hold is active for both the main and companion APKs while protected peer work runs.** Shared-UID/package changes can interrupt Termux and sibling processes even when only installing a new plugin. This workflow does not lift that hold. Checkpoint work and follow the main [installation boundary](../FOLD_PROFILE.md#installation-boundary), signer checks, backups and recovery readiness before the owner separately authorizes any Android package change. Never uninstall to work around a signing conflict.

After the hold is explicitly lifted, the owner can inspect the separately downloaded APK/checksum/provenance and use Android's ordinary installer with explicit confirmation. The target-28 app may show an old-app warning on modern Android. Confirm package/version and Fold certificate, not just a filename. Do not assume that changing or reinstalling the main APK is required to install this companion, or that a main APK update installs the companion automatically.

Termux:API exposes much more than clipboard functions and shares the Fold UID trust boundary. Its upstream manifest requests optional sensitive Android capabilities (for example contacts, camera, microphone, phone/SMS, location, storage and notification access). Ordinary Android runtime/special-access permission controls still apply; declaring a permission does not automatically grant it, and some protected permissions are unavailable to ordinary apps. Review shared-UID privacy implications and enable only the APIs/access actually needed. Do **not** auto-grant all permissions or turn on unrelated services to make clipboard access work.

Clipboard functionality remains **unverified** until the owner performs a safe physical probe after authorized installation: deliberately use non-sensitive, disposable content, account for foreground/background restrictions, confirm both canonical set/get routes and expected errors without logging existing clipboard contents, and restore user state only with consent. Do not read, print, upload, or overwrite the user's real clipboard as part of CI or this implementation. Record actual results, including permission prompts and modern-Android limitations, rather than inferring success from an APK build or the presence of the CLI.

## Verification / remaining gates

On 2026-10-24, workflow revision `d1a9bbbac8e698d07aa896606c0f37e8a3bb098d` passed [real hosted qualification](https://github.com/fitchmultz/termux-app/actions/runs/40131530139): both complete AM/shared-library→local-Maven→API chains, Java 11/21 switching, original producer native builds, generated POM/module graph and ABI checks, exclusive local AAR hash audits, source-file preservation and final APK validators succeeded. The two independent unsigned API APKs were byte-identical: **3,799,357 bytes**, SHA-256 `abc5f6f555c568a6c6e5a139ca04936e0213092843d98e29b4f6de1c54a3c833`. Intermediate shared/emulator AAR hashes differed and were recorded truthfully; neither their equality nor historical JitPack binary equality is claimed.

The parent downloaded the actual candidate and checked its reviewed contract/overlay hashes, bounded public provenance, unsigned archive, package/shared UID/version/SDK, non-debuggable/non-exported receiver, no native payload, no valid signature and ZIP alignment. The paired [Fold checks](https://github.com/fitchmultz/termux-app/actions/runs/40131530096) passed all four jobs; actual reports contain **223 main-project tests** with no failures/errors/skips. The API release unit-test task remains **`NO-SOURCE`**, not an additional passing suite. The companion signer was **skipped**; no signed companion or physical clipboard result is established.

Earlier qualifications failed first on [repository-name capture](https://github.com/fitchmultz/termux-app/actions/runs/40128531643), then on [ambiguous project-artifact enumeration](https://github.com/fitchmultz/termux-app/actions/runs/40129535723). Explicit publication-repository receivers and the external raw-AAR artifact view correct those failures without changing production configuration attributes. Exact before/after core-model RED/GREEN evidence and six graph/version/classifier/type/hash rejection controls support the latter correction; model sentinels are not real AARs. Public Gradle 7.2/AGP 4.2 sources and the privilege boundary received independent review.

Local actionlint/shell/Python/Groovy/source/link checks passed. Compatible-host [ShellCheck qualification](https://github.com/fitchmultz/termux-app/actions/runs/40128686335) passed all eight literal scripts with the hosted image's actual ShellCheck **0.9.0**; those scripts are unchanged through the qualified workflow revision. Gradle dependency reports may exit 0 with `FAILED` edges, so their exit status alone is never a build gate. No package/tool installation into the live prefix was needed.

On ARM64 Termux, use Java 21 and an isolated temporary Gradle home for configuration/dependency reports only; never run the SDK's x86_64 aapt/zipalign binaries or attempt a local APK build. Missing SDK platforms/dependencies and repository-resolution errors must be reported without changing the live SDK/prefix.

Promotion still requires current-head project gates and resolution of every actionable finding. The fixed-revision evidence above does not automatically qualify later source/tool/recipe changes. A successful unprivileged build does **not** exercise the opt-in signer; a separately authorized main-branch signing run must pass its same-run artifact/provenance checks and independently verified signed-APK gates. Keep both installation holds, distribution decisions and physical validation independent of build/signing success.
