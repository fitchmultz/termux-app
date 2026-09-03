# Downstream changes

This is the canonical concise inventory of intentional differences from the audited Termux upstream base. Update it in the same commit whenever a fix, feature, default, release constraint, or operational policy changes.

## Scope and release profile

- Targets only the owner's Samsung `SM-F976U1`, Android 17, ARM64, Samsung Keyboard, and Fold/Pop-up View workflows.
- Keeps package ID `com.termux` and prefix `/data/data/com.termux/files/usr`; this is an in-place fork, not a side-by-side package.
- Uses label `Termux Fold`; shipped source is `0.119.0-fold.5` (`2026090305`) at tag `fold-v0.119.0-fold.5`.
- Packages only `arm64-v8a` with the `apt-android-7` bootstrap; split release APKs are disabled.
- Retains target SDK 28 for direct execution of programs from the writable Termux prefix. Modern targets require routing execution through Android's system linker plus a separately patched package ecosystem; forced-linker tests currently break Node test workers and `age-keygen`. Android 17 may therefore show an old-app or **Install anyway** warning.
- Uses a dedicated Android signing identity. Signing material must never be committed or made public, but protected GitHub Actions secrets and signed GitHub Releases are allowed when deliberately configured.

## Fixes

- Removes a session from the drawer after its confirmed long-press exit, while retaining unexpectedly failed sessions for inspection.
- Preserves the Android 11+ all-files/storage-permission setup path used by the previously installed F-Droid beta.
- Implements DEC synchronized output mode 2026, including DECSET/DECRST, DECRQM, deferred text/color/cursor presentation, reset/process cleanup, and cursor-blink suppression.
- Adds a two-second synchronized-output watchdog so a malformed or terminated application cannot freeze rendering indefinitely.
- Retains a bounded immutable copy of the last completed visible frame while synchronized output is active, preventing Samsung Pop-up View from drawing partial PTY updates.
- Preserves simultaneous text-field visibility and unsent text through activity recreation, including when the row is hidden.
- Routes drawer layout, Back handling, hardware shortcuts, and the `DRAWER` extra key through one logical start/end position contract.

## Features

- Long-pressing a session row opens actions to rename it or exit it through the existing confirmation dialog.

- Adds an optional real Android `EditText` stacked with the extra-key toolbar, so Samsung composition remains available while terminal keys stay visible.
- Adds the `TEXTBAR` extra-key action to focus/show that Android text field without replacing the extra-key page.
- Adds typed `TerminalSessionDrawerPosition` values (`start`/`end`) instead of passing unchecked drawer strings through app code.
- Centralizes Termux-specific extra-key actions (`DRAWER`, `KEYBOARD`, `TEXTBAR`) for defaults, display, dispatch, and tests.
- Raises only OSC 52's parser bound to 512 KiB, supporting roughly 384 KiB clipboard writes while other OSC and device-control strings retain the upstream 8 KiB bound.

## Opinionated defaults

- Enables Samsung character-based terminal input for immediate command typing.
- Enables the simultaneous toolbar text field and places the session drawer at logical `end` (right in this profile).
- Uses two rows of six controls: `ESC`, `TAB`, `CTRL/PREV`, `ALT/NEXT`, `NEXT`, `DRAWER`; then `HOME`, `LEFT`, `DOWN/PGDN`, `UP/PGUP`, `RIGHT`, `KEYBOARD/TEXTBAR`.
- Keeps explicit keyboard controls because full-screen TUIs such as Pi use terminal mouse tracking and consume terminal taps.

## Contracts, tests, and repository policy

- Defines one immutable root Gradle profile for version, label, bootstrap variant, and ABI filters across modules.
- Extends property schemas and tests so keys, accepted values, defaults, parsers, and UI consumers cannot silently drift.
- Tests the Fold extra-key grammar, storage policy, drawer enum/default, simultaneous-input defaults, synchronized-output behavior, and bounded large OSC 52 clipboard writes.
- Uses `fold/main` as the GitHub default; `master` remains an audited reference point, not an automatic synchronization target.
- Treats this as a permanent personal appliance fork. Upstream PRs and wholesale rebases/syncs are not goals; relevant security or compatibility fixes are selectively reviewed and cherry-picked.
- Keeps `Fold checks` read-only for wrapper validation, full unit tests, packaged metadata/ABI/signature checks, and two reproducible unsigned builds.
- Provides a manually dispatched `Fold signed release` workflow: an unprivileged job builds twice, then a fresh environment-scoped job (with no reviewer/wait gate) signs and verifies; public Release publication is a separate explicit boolean gate and includes only the signed APK/checksum/provenance. Inherited upstream artifact/release/dependency/JitPack workflows remain disabled.
