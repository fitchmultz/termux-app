# Downstream changes

This is the canonical concise inventory of intentional differences from the audited Termux upstream base. Update it in the same commit whenever a fix, feature, default, release constraint, or operational policy changes.

## Scope and release profile

- Prioritizes the owner's Z Fold 8 (`SM-F971U1`, Android 17), retains the Ultra (`SM-F976U1`) as a regression target, and welcomes other compatible devices without model-name gating. The signed APK remains ARM64-only.
- Keeps package ID `com.termux` and prefix `/data/data/com.termux/files/usr`; this is an in-place fork, not a side-by-side package.
- Uses label `Termux Fold`; this branch's source release profile is `0.119.0-fold.10` (`2026102410`). PR integration, signed publication, and physical validation are separate steps.
- Packages only `arm64-v8a` with the `apt-android-7` bootstrap; split release APKs are disabled.
- Retains target SDK 28 for direct execution of programs from the writable Termux prefix. Modern targets require routing execution through Android's system linker plus a separately patched package ecosystem; forced-linker tests currently break Node test workers and `age-keygen`. Android 17 may therefore show an old-app or **Install anyway** warning.
- Uses a dedicated Android signing identity. Signing material must never be committed or made public, but protected GitHub Actions secrets and signed GitHub Releases are allowed when deliberately configured.

## Fixes

- Adapts upstream `74ab5126` to retain each session's scroll offset across session/pane replacement, activity-view restoration, keyboard/window resizing, and foreground refresh, bounded by remaining history. SCROLL reconciles background/detached output once; cancelled evicted selections follow live output unless scroll-locked. All view refreshes defer unfinished synchronized frames, while normal new output still follows the terminal.

- Ports upstream `47c473f9` to clear stale wrapped-line metadata when an erase reaches the right edge, preserving newlines in copied terminal text.
- Ports upstream `5532cc4f` to keep UTF-16 row lengths in an `int`, preventing overflow with supplementary combining text in wide terminal rows.
- Selectively adapts upstream `fd2ab4fc` string-framing corrections: only `ESC \` terminates a DCS, and OSC escape-overflow cannot resume an aborted sequence. Retains existing parser limits and does not add image protocols or fast paths.

- Selectively incorporates upstream `45844885`, `b4a2c32c`, and `084d709f`: prevents fullscreen keyboard-margin predictions from resizing split-screen/pop-up windows, handles root file-share MIME queries, and pairs JNI string releases with the correct arguments. Also normalizes MIME extensions independently of the user's locale in sharing and document-provider paths.

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

- This source branch keeps the soft keyboard hidden on return from another app and does not open it merely to switch existing sessions. It cancels pending show requests when the keyboard is hidden and clears a carried-over IME on the returning window, while retaining existing status-bar inset handling. Initial launch, new sessions, and deliberate keyboard controls remain available; set `show-soft-keyboard-on-navigation = true` in `~/.termux/termux.properties` and run `termux-reload-settings` to restore the old automatic requests. Existing startup-hide and keyboard-disable settings still take precedence.
- Enables Samsung character-based terminal input for immediate command typing.
- Makes the Evidence Dock available alongside extra keys, initially collapsed, and places the session drawer at logical `end` (right in this profile).
- Uses `ESC`, `TAB`, `CTRL/PREV`, `ALT/NEXT`, `NEXT`, `DRAWER`; then `HOME`, `LEFT`, `UP/PGUP`, `DOWN/PGDN`, `RIGHT`, `KEYBOARD/TEXTBAR`. The default matrix reflows to one row only when every label and at least 48dp per touch target fit. Custom matrices keep their configured rows; toolbar height respects a 48dp minimum. Reflow preserves the same button instances, modifiers, macros, and popups.
- Keeps explicit keyboard controls because full-screen TUIs such as Pi use terminal mouse tracking and consume terminal taps.

## Contracts, tests, and repository policy

- Provides a [pinned-source Termux:API companion workflow](docs/TERMUX_API_COMPANION.md): two independent exact-source library→local-Maven→API release chains, narrow publication/resolution-only overlays, strict metadata/APK/provenance/reproducibility gates, and default-off, main-branch-only isolated signing with the existing Fold key. Explicit publication-repository receivers avoid Groovy module-name shadowing; the source audit selects external raw AARs without changing production project variants. CLT 23 skips only the obsolete `--licenses` handshake, not package-license handling. Short-lived artifacts only; workflow integration does not publish/install the companion or establish physical clipboard access. Signing, distribution, installation/hold clearance and permissions remain separate owner-controlled steps.

- Retains the unused Fold 6 tag after cancelling publication before signing to fix a cross-pane synchronized-background regression; release tags are not rewritten.

- Defines one immutable root Gradle profile for version, label, bootstrap variant, and ABI filters across modules.
- Extends property schemas and tests so keys, accepted values, defaults, parsers, and UI consumers cannot silently drift.
- Tests the Fold extra-key grammar, storage policy, drawer enum/default, simultaneous-input defaults, synchronized-output behavior, and bounded large OSC 52 clipboard writes.
- Uses `fold/main` as the GitHub default; `master` remains an audited reference point, not an automatic synchronization target. Fork CLI/API operations explicitly name the intended repository rather than relying on upstream-prone inference.
- Treats this as a permanent personal appliance fork. Upstream PRs and wholesale rebases/syncs are not goals; relevant security or compatibility fixes are selectively reviewed and cherry-picked.
- Keeps `Fold checks` read-only for wrapper validation, full unit tests, packaged metadata/ABI/signature checks, and two reproducible unsigned builds. Synthetic workbench render images and unit-test reports are retained briefly as CI artifacts; no private terminal contents are captured.
- Routes all eight Actions jobs to the owner's persistent private M1 Linux ARM64 runner, using digest-pinned Ubuntu 24.04 amd64 job containers with Rosetta and read-only ARM64 action-runtime libraries; offline jobs queue without a hosted fallback. Job-private SDK/work/Gradle directories, per-command exact-checkout Git ownership exceptions, explicit x64 Java 11/21, verified GitHub CLI 2.103.2 in signers, and truthful companion host/container provenance preserve the existing build/signing gates. Migration does not re-enable Actions, publish, install, or establish Android runtime qualification. See [runner policy](docs/CI_RUNNER.md).
- Gives every SDK-using M1 job a job-private `ANDROID_USER_HOME` before SDK setup, including both signers, without patching Google's tools or deleting shared runner state.
- Provides a manually dispatched `Fold signed release` workflow: an unprivileged job builds twice, then a separate environment-scoped job (with no reviewer/wait gate) signs and verifies; public Release publication is a separate explicit boolean gate and includes only the signed APK/checksum/provenance. Inherited upstream artifact/release/dependency/JitPack workflows are removed.

- Keeps installation owner-controlled with signer/backup/recovery and physical-test readiness. Checkpoint running work before APK replacement; never uninstall to update or assume Android accepts lower-code APK rollback. See [installation boundary](FOLD_PROFILE.md#installation-boundary).

## Offline default tools

- Replaces the single upstream bootstrap download with a native Android-7 ARM64 package snapshot: Git, OpenSSH/SFTP, tmux, jq, ripgrep, fd, fzf, rsync, tree, zip, wget, zsh/completions, lsd, bat, git-delta, direnv, and git-lfs join the existing baseline, available offline on fresh installation. Versioning, signed publication, and physical installation remain separate release steps.
- Authenticates maintenance indices with the pinned Termux Releases public key; locks every package URL/version/architecture/SHA-256 and the upstream generator sources. Build-private generation verifies all inputs and package/control extraction paths, uses only a local snapshot, canonicalizes the compressed archive, validates dpkg files/checksums plus production ELF/API/library closure, and publishes atomically. Adds libmount/libblkid for an undeclared inherited util-linux runtime dependency; keeps optional upstream installed-test fixtures without bundling their compiler/ASan runtimes.
- This branch recovers unavailable locked inputs from a content-addressed public archive, verifies the archive and every member against the reviewed lock/profile, and retains the builder's cache checks and offline behavior. Tagged signing restores matching inputs before tests and after clean without changing the tagged source or existing app releases.
- Keeps nonempty existing prefixes untouched by APK updates; documents explicit `pkg install` migration and separate Node/Python versioned development and Termux:API Android companion boundaries. Bash remains the default, without aliases, direnv hooks/allow, user-dotfile activation, or global Git changes. Neither sshd nor ssh-agent auto-starts. Ordinary on-device offline postinst creates SSH host keys and delta completions, and registers bat's pager alternative below less; no keys are built into the APK.
- Adds the locked `termux-api` 0.60.0 client to fresh-prefix defaults with canonical clipboard get/set delivery contracts; preserves all 119 prior records/provenance (120 packages total). Two clean ZIPs, second offline, match; payload/ELF and production Java executable delivery checks invoke no API or clipboard operation. The client has no maintainer scripts/startup hooks; the same-Fold-key Android companion and only needed permissions remain separate. Existing prefixes/configuration are unchanged; client-only delivery does not establish working clipboard access.
- Adds runner/security and real-payload negative controls plus a two-generation/offline bootstrap CI gate without removing full unit, debug APK, identity/API/ABI/signature, reproducible unsigned APK, or isolated signing checks. Physical offline bootstrap/heap/device checks remain explicitly unperformed until authorized installation. See [bootstrap maintenance](tools/bootstrap/README.md).
- Tracks the bootstrap ZIP as a prerequisite of its native assembly object, preventing stale embedded tools after a no-clean lock refresh; tests actual NDK A→B object bytes without source/makefile changes.
- Preserves package owner-execute bits through a generated executable manifest and a focused production staging extractor, instead of granting execute by path prefix. Real script/Git-hook execution tests isolate host Git/configuration and protect service scripts and hook templates without broadening ordinary files or enabling services; the nonempty-prefix early return and symlink/rename commit path remain.
- Initializes the existing screen-awake regression's backing preference before its dual-mode wrapper, avoiding Android-28 cold-loader races. The test asserts its initial state and retains real both-pane/persisted toggles; no runtime preference or view policy changes.

## Dependency and build maintenance

- Unit tests can use job-private, SHA-256-verified Robolectric native-runtime and Android 7/9/17 runtime seeds; unavailable artifacts use the normal resolvers and mismatches fail. Other versions, companion source rounds, reproducible builds, and isolated signers remain unchanged.

- Hosted CLT 23 setup skips the removed `sdkmanager --licenses` handshake, whose immediate exit can crash the setup action's stdin writer with `EPIPE`; actual SDK package installation and its license handling remain required in CI and isolated signing.

- Explicitly authorizes frequent proactive dependency/tool/bootstrap/default-package/action maintenance and useful stable-release API/convention adoption, without bypassing compatibility, read-only CI, isolated signing, reproducibility, backup/recovery, or owner-controlled integration/release gates.
- Uses AGP 9.4.1's public DSL, Gradle 9.8.2 with verified distribution/wrapper checksums, compile SDK 37.2 via the public minor-release DSL, build-tools 37.0.0, NDK r30, and explicitly provisioned stable command-line tools 23.0 in CI. Retains target 28, minimum 24, ARM64, and package identity.
- Sets all four modules' Java source/bytecode baseline to 17 with an explicit Java 21 compiler/test toolchain and current stable 21.x CI resolution. Supported AGP/D8 and core-library desugaring 2.1.5 retain the Android API floor; CI tests include Android 7/17 regressions and the required Java 21 FileDescriptor export for Robolectric's Android 17 bridge.
- Records all 19 direct Maven coordinates and tool/action decisions in the dated [dependency audit](docs/DEPENDENCY_AUDIT.md); those libraries were already current stable at that audit. The locked native package snapshot and its separate maintenance process are documented in [bootstrap maintenance](tools/bootstrap/README.md).
- Removes obsolete library-publishing/JitPack configuration and unused library ProGuard files, duplicate app Markdown dependencies and the now-transitive Guava conflict placeholder, and an inherited instrumented package-name template test. Guava remains an API dependency because shared property schemas expose its map types; sharing helpers use AndroidX's nullable annotation.
- Pins GitHub Actions by commit and explicitly provisions the SDK. Isolated signing retains all signature/reproducibility gates, including build-tools 37's per-scheme certificate output and a verifier-only API 23 floor for v1. The README distinguishes Fold-signed updates from upstream reference guidance.
