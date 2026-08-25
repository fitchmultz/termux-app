# Downstream changes

This is the canonical concise inventory of intentional differences from the audited Termux upstream base. Update it in the same commit whenever a fix, feature, default, release constraint, or operational policy changes.

## Scope and release profile

- Targets only the owner's Samsung `SM-F976U1`, Android 17, ARM64, Samsung Keyboard, and Fold/Pop-up View workflows.
- Keeps package ID `com.termux` and prefix `/data/data/com.termux/files/usr`; this is an in-place fork, not a side-by-side package.
- Uses label `Termux Fold`; shipped source is `0.119.0-fold.1` (`2026082001`) at tag `fold-v0.119.0-fold.1-rc1`.
- Packages only `arm64-v8a` with the `apt-android-7` bootstrap; split release APKs are disabled.
- Uses a private dedicated Android signing identity. No signing material or APK is stored in GitHub, CI, or the public repository.

## Fixes

- Preserves the Android 11+ all-files/storage-permission setup path used by the previously installed F-Droid beta.
- Implements DEC synchronized output mode 2026, including DECSET/DECRST, DECRQM, deferred text/color/cursor presentation, reset/process cleanup, and cursor-blink suppression.
- Adds a two-second synchronized-output watchdog so a malformed or terminated application cannot freeze rendering indefinitely.
- Preserves simultaneous text-field visibility and unsent text through activity recreation, including when the row is hidden.
- Routes drawer layout, Back handling, hardware shortcuts, and the `DRAWER` extra key through one logical start/end position contract.

## Features

- Adds an optional real Android `EditText` stacked with the extra-key toolbar, so Samsung composition remains available while terminal keys stay visible.
- Adds the `TEXTBAR` extra-key action to focus/show that Android text field without replacing the extra-key page.
- Adds typed `TerminalSessionDrawerPosition` values (`start`/`end`) instead of passing unchecked drawer strings through app code.
- Centralizes Termux-specific extra-key actions (`DRAWER`, `KEYBOARD`, `TEXTBAR`) for defaults, display, dispatch, and tests.

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
- Keeps one read-only `Fold checks` workflow for wrapper validation, full unit tests, packaged metadata/ABI/signature checks, and two reproducible unsigned builds. Inherited artifact/release/dependency/JitPack workflows remain disabled.

## In development, not yet merged into `fold/main`

- `fold/synchronized-output-snapshot` / `0.119.0-fold.2` retains a bounded immutable copy of the last completed visible terminal frame while mode 2026 is active. This addresses Samsung portrait Pop-up View forcing `onDraw()` between PTY chunks; physical portrait/landscape validation is still required.
- `fold/clipboard-osc52` / `0.119.0-fold.3` raises only OSC 52's parser bound to 512 KiB, enough for roughly 384 KiB of UTF-8 clipboard text while staying below Android's Binder transaction limit. Other OSC and device-control strings retain the upstream 8 KiB bound.
