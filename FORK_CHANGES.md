# Downstream changes

This is the canonical concise inventory of intentional differences from the audited Termux upstream base. Update it in the same commit whenever a fix, feature, default, release constraint, or operational policy changes.

## Scope and release profile

- Prioritizes the owner's Z Fold 8 (`SM-F971U1`, Android 17), retains the Ultra (`SM-F976U1`) as a regression target, and welcomes other compatible devices without model-name gating. The signed APK remains ARM64-only.
- Keeps package ID `com.termux` and prefix `/data/data/com.termux/files/usr`; this is an in-place fork, not a side-by-side package.
- Uses label `Termux Fold`; the last published release is `0.119.0-fold.7`. The Fold 8 candidate is `0.119.0-fold.8` (`2026091708`); publication and physical validation are separate gates.
- Packages only `arm64-v8a` with the `apt-android-7` bootstrap; split release APKs are disabled.
- Retains target SDK 28 for direct execution of programs from the writable Termux prefix. Modern targets require routing execution through Android's system linker plus a separately patched package ecosystem; forced-linker tests currently break Node test workers and `age-keygen`. Android 17 may therefore show an old-app or **Install anyway** warning.
- Uses a dedicated Android signing identity. Signing material must never be committed or made public, but protected GitHub Actions secrets and signed GitHub Releases are allowed when deliberately configured.

## Fixes

- Fold 8 candidate: makes Dock/Panes labels readable in both app themes, combines the single-visible-pane title with those actions, and preserves separate focus headers when both panes are visible.

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
- Fold 8 candidate: uses `ESC`, `TAB`, `CTRL/PREV`, `ALT/NEXT`, `NEXT`, `DRAWER`; then `HOME`, `LEFT`, `UP/PGUP`, `DOWN/PGDN`, `RIGHT`, `KEYBOARD/TEXTBAR`. The default matrix reflows to one row only when every label and at least 48dp per touch target fit. Custom matrices keep their configured rows; toolbar height respects a 48dp minimum. Reflow preserves the same button instances, modifiers, macros, and popups.
- Keeps explicit keyboard controls because full-screen TUIs such as Pi use terminal mouse tracking and consume terminal taps.

## Contracts, tests, and repository policy

- Retains the unused Fold 6 tag after cancelling publication before signing to fix a cross-pane synchronized-background regression; release tags are not rewritten.

- Defines one immutable root Gradle profile for version, label, bootstrap variant, and ABI filters across modules.
- Extends property schemas and tests so keys, accepted values, defaults, parsers, and UI consumers cannot silently drift.
- Tests the Fold extra-key grammar, storage policy, drawer enum/default, simultaneous-input defaults, synchronized-output behavior, and bounded large OSC 52 clipboard writes.
- Uses `fold/main` as the GitHub default; `master` remains an audited reference point, not an automatic synchronization target.
- Treats this as a permanent personal appliance fork. Upstream PRs and wholesale rebases/syncs are not goals; relevant security or compatibility fixes are selectively reviewed and cherry-picked.
- Keeps `Fold checks` read-only for wrapper validation, full unit tests, packaged metadata/ABI/signature checks, and two reproducible unsigned builds. Synthetic workbench render images and unit-test reports are retained briefly as CI artifacts; no private terminal contents are captured.
- Provides a manually dispatched `Fold signed release` workflow: an unprivileged job builds twice, then a fresh environment-scoped job (with no reviewer/wait gate) signs and verifies; public Release publication is a separate explicit boolean gate and includes only the signed APK/checksum/provenance. Inherited upstream artifact/release/dependency/JitPack workflows remain disabled.
