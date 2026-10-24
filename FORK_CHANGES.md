# Downstream changes

This is the canonical concise inventory of intentional differences from the audited Termux upstream base. Update it in the same commit whenever a fix, feature, default, release constraint, or operational policy changes.

## Scope and release profile

- Prioritizes the owner's Z Fold 8 (`SM-F971U1`, Android 17), retains the Ultra (`SM-F976U1`) as a regression target, and welcomes other compatible devices without model-name gating. The signed APK remains ARM64-only.
- Keeps package ID `com.termux` and prefix `/data/data/com.termux/files/usr`; this is an in-place fork, not a side-by-side package.
- Uses label `Termux Fold`; this branch's source release profile is `0.119.0-fold.9` (`2026101309`). PR integration, signed publication, and physical validation are separate steps.
- Packages only `arm64-v8a` with the `apt-android-7` bootstrap; split release APKs are disabled.
- Retains target SDK 28 for direct execution of programs from the writable Termux prefix. Modern targets require routing execution through Android's system linker plus a separately patched package ecosystem; forced-linker tests currently break Node test workers and `age-keygen`. Android 17 may therefore show an old-app or **Install anyway** warning.
- Uses a dedicated Android signing identity. Signing material must never be committed or made public, but protected GitHub Actions secrets and signed GitHub Releases are allowed when deliberately configured.

## Fixes

- Candidate (`fold/scroll-lifecycle`, not yet integrated): adapts upstream `74ab5126` to retain each session's scroll offset across session/pane replacement, activity-view restoration, keyboard/window resizing, and foreground refresh, bounded by remaining history. SCROLL reconciles background/detached output once; cancelled evicted selections follow live output unless scroll-locked. All view refreshes defer unfinished synchronized frames, while normal new output still follows the terminal.

- Candidate on `fold/upstream-audit` (not yet integrated into `fold/main`): ports upstream `47c473f9` to clear stale wrapped-line metadata when an erase reaches the right edge, preserving newlines in copied terminal text.
- Candidate on `fold/upstream-audit`: ports upstream `5532cc4f` to keep UTF-16 row lengths in an `int`, preventing overflow with supplementary combining text in wide terminal rows.
- Candidate on `fold/upstream-audit`: selectively adapts upstream `fd2ab4fc` string-framing corrections: only `ESC \` terminates a DCS, and OSC escape-overflow cannot resume an aborted sequence. Retains existing parser limits and does not add image protocols or fast paths.

- Makes Dock/Panes labels readable in both app themes, combines the single-visible-pane title with those actions, and preserves separate focus headers when both panes are visible.

- Includes upstream `3b66f87`: rejects file-based RUN_COMMAND error results until the external-app policy has passed; Android's RUN_COMMAND permission remains required.

- Removes a session from the drawer after its confirmed long-press exit, while retaining unexpectedly failed sessions for inspection.
- Preserves the Android 11+ all-files/storage-permission setup path used by the previously installed F-Droid beta.
- Implements DEC synchronized output mode 2026, including DECSET/DECRST, DECRQM, deferred text/color/cursor presentation, reset/process cleanup, and cursor-blink suppression.
- Adds a two-second synchronized-output watchdog so a malformed or terminated application cannot freeze rendering indefinitely.
- Retains a bounded immutable copy of the last completed visible frame while synchronized output is active, preventing Samsung Pop-up View from drawing partial PTY updates. Each pane's Android background uses that same completed palette, including when another pane changes colors. Pane changes transfer keyboard focus and cancel stale gestures; the screen-awake setting applies to both views.
- Preserves simultaneous text-field visibility and unsent text through activity recreation, including when the row is hidden.
- Routes drawer layout, Back handling, hardware shortcuts, and the `DRAWER` extra key through one logical start/end position contract.

## Features

- Adds native two-pane sessions with independent terminal views, active-pane input routing, draggable sizing, layout/swap/maximize controls, and compact-window collapse without ending the hidden session. Compact transitions are applied before measurement so the remaining pane fills the window.
- Adds a per-session Evidence Dock with native multiline composition, private file imports, Android share intake, saved-draft recovery, and explicit Insert versus confirmed Send. App-owned import state survives activity replacement; draft persistence and completion are committed together. Saved drafts can be explicitly recovered or deleted, and real-stream tests cover atomic import success/failure. Pending share selections survive recreation, while completed shares are not replayed. Imports never execute commands or upload content.
- Rejects multiline insertion when the receiving program has not enabled bracketed paste, and omits clipboard payloads from OSC 52 error logs.

- Long-pressing a session row opens actions to rename it or exit it through the existing confirmation dialog.

- Adds an optional real Android `EditText` stacked with the extra-key toolbar, so Samsung composition remains available while terminal keys stay visible.
- Adds the `TEXTBAR` extra-key action to focus/show that Android text field without replacing the extra-key page.
- Adds typed `TerminalSessionDrawerPosition` values (`start`/`end`) instead of passing unchecked drawer strings through app code.
- Centralizes Termux-specific extra-key actions (`DRAWER`, `KEYBOARD`, `TEXTBAR`) for defaults, display, dispatch, and tests.
- Raises only OSC 52's parser bound to 512 KiB, supporting roughly 384 KiB clipboard writes while other OSC and device-control strings retain the upstream 8 KiB bound.

## Opinionated defaults

- Enables Samsung character-based terminal input for immediate command typing.
- Makes the Evidence Dock available alongside extra keys, initially collapsed, and places the session drawer at logical `end` (right in this profile).
- Uses `ESC`, `TAB`, `CTRL/PREV`, `ALT/NEXT`, `NEXT`, `DRAWER`; then `HOME`, `LEFT`, `UP/PGUP`, `DOWN/PGDN`, `RIGHT`, `KEYBOARD/TEXTBAR`. The default matrix reflows to one row only when every label and at least 48dp per touch target fit. Custom matrices keep their configured rows; toolbar height respects a 48dp minimum. Reflow preserves the same button instances, modifiers, macros, and popups.
- Keeps explicit keyboard controls because full-screen TUIs such as Pi use terminal mouse tracking and consume terminal taps.

## Contracts, tests, and repository policy

- Diagnostic branch only (not shipped): makes one bounded, unprivileged hosted request per pinned Termux:API dependency descriptor/status/log to distinguish missing JitPack artifacts from phone-specific egress; no checkout, build, signing, installation, or helper reset.

- Retains the unused Fold 6 tag after cancelling publication before signing to fix a cross-pane synchronized-background regression; release tags are not rewritten.

- Defines one immutable root Gradle profile for version, label, bootstrap variant, and ABI filters across modules.
- Extends property schemas and tests so keys, accepted values, defaults, parsers, and UI consumers cannot silently drift.
- Tests the Fold extra-key grammar, storage policy, drawer enum/default, simultaneous-input defaults, synchronized-output behavior, and bounded large OSC 52 clipboard writes.
- Uses `fold/main` as the GitHub default; `master` remains an audited reference point, not an automatic synchronization target.
- Treats this as a permanent personal appliance fork. Upstream PRs and wholesale rebases/syncs are not goals; relevant security or compatibility fixes are selectively reviewed and cherry-picked.
- Keeps `Fold checks` read-only for wrapper validation, full unit tests, packaged metadata/ABI/signature checks, and two reproducible unsigned builds. Synthetic workbench render images and unit-test reports are retained briefly as CI artifacts; no private terminal contents are captured.
- Provides a manually dispatched `Fold signed release` workflow: an unprivileged job builds twice, then a fresh environment-scoped job (with no reviewer/wait gate) signs and verifies; public Release publication is a separate explicit boolean gate and includes only the signed APK/checksum/provenance. Inherited upstream artifact/release/dependency/JitPack workflows are removed.

## Maintenance refresh (unmerged candidate)

- Explicitly authorizes frequent proactive dependency/tool/bootstrap/default-package/action maintenance and useful stable-release API/convention adoption, without bypassing compatibility, read-only CI, isolated signing, reproducibility, backup/rollback, or owner-controlled integration/release gates.
- Updates Gradle to 9.8.2 with its official distribution checksum, Java setup to SHA-pinned v6.1.0 with current Java 21 patch resolution, command-line tools to stable 23.0, and compile SDK to stable 37.2 using AGP's public minor-release DSL. Retains target 28, minimum 24, ARM64, package identity, and the source version.
- Raises all four modules' Java source/bytecode baseline from 8 to 17 and explicitly selects the Java 21 compiler/test toolchain, using supported AGP/D8 desugaring rather than suppressing JDK 21's obsolete Java 8 warnings. Android API availability remains constrained by minimum 24, existing core-library desugaring, and hosted Android 7 regressions.
- Records the complete direct-dependency/tool audit in [docs/DEPENDENCY_AUDIT.md](docs/DEPENDENCY_AUDIT.md). Direct Maven libraries and the remaining actions are already current stable; the newer verified October 18 bootstrap is reserved for the parallel default-tools integration rather than overwritten here.

## Package refresh (`0.119.0-fold.9`)

This section inventories the `fold/package-refresh` source changes, integrated into `fold/main` on PR merge. Automated release checks do not establish physical Fold8 validation; device testing follows the signed APK update.

- Advances the source profile to `0.119.0-fold.9` (`2026101309`) without changing the package ID, signing identity, ARM64-only ABI, target SDK 28, panes, Dock, or input/rendering defaults.
- Bundles the upstream `2026.10.11-r1+apt.android-7` ARM64 bootstrap with its pinned SHA-256. Existing installations still need `pkg update && pkg upgrade`; updating the APK never replaces an existing package prefix.
- Updates all direct dependencies with newer stable releases: AndroidX Annotation/Core/AppCompat/ViewPager/WindowManager, Material, Guava, Commons IO, HiddenApiBypass, and Robolectric. DrawerLayout, Preference, Markwon, JUnit, and termux-am-library already use their latest stable releases.
- Uses AGP 9.4.1's public DSL, Gradle 9.8.1 with verified distribution/wrapper checksums, compile SDK 37, build-tools 37.0.0, and NDK r30. CI uses Java 21 to exercise regressions on Android 7 and Android 17; the app test JVM exports the JDK file-descriptor API required by Robolectric's Android 17 bridge.
- Aligns the installation minimum with the only supported Android 7 bootstrap: API 24. Desugaring 2.1.5 supplies modern Java/NIO APIs on that floor; target SDK 28 remains intentional.
- Downloads and verifies the bootstrap before both Java and native builds, closes streams, and replaces cached archives only after a successful checksum verification. Clean builds no longer need a manual bootstrap-download workaround.
- Selectively incorporates upstream `45844885`, `b4a2c32c`, and `084d709f`: prevents fullscreen keyboard-margin predictions from resizing split-screen/pop-up windows, handles root file-share MIME queries, and pairs JNI string releases with the correct arguments. Also normalizes MIME extensions independently of the user's locale in sharing and document-provider paths.
- Removes five disabled upstream workflows, including tag-deleting release automation; obsolete library-publishing/JitPack configuration and unused library ProGuard files; duplicate app Markdown dependencies and the now-transitive Guava conflict placeholder; and an inherited instrumented package-name template test. Guava remains an API dependency because shared property schemas expose its map types; sharing helpers use the existing AndroidX nullable annotation instead of relying on Guava's retired annotation dependency.
- Pins current GitHub Actions by commit, explicitly provisions the Android SDK instead of assuming runner tools are on PATH, retains isolated signing and all signature/reproducibility gates (including build-tools 37's per-scheme certificate output and a verifier-only API 23 floor for v1), and verifies the packaged minimum/target SDKs. The README now distinguishes Fold-signed updates from upstream installation guidance.
