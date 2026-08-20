# Fold device profile

This is an opinionated personal fork of Termux for one deployment target:

- Samsung `SM-F976U1`
- Android 17
- ARM64
- Samsung Keyboard
- narrow cover display and unfolded inner display
- Pi, tmux, Node.js, Git, SSH, and localhost-service workflows

Compatibility with other devices, Android releases, keyboards, architectures, package variants, or user preferences is not a release criterion. Other people may use the public source, but downstream decisions optimize this device first.

See [FORK_CHANGES.md](FORK_CHANGES.md) for the canonical concise list of shipped and pending deviations. Every downstream behavior, default, release constraint, or operational-policy change must update that inventory in the same commit.

## Defaults

The integrated Fold profile:

- identifies itself as `Termux Fold` version `0.119.0-fold.1` with monotonic version code `2026082001`;
- builds and packages only `arm64-v8a` with the Android 7 bootstrap variant;
- enables Samsung character-based terminal input;
- shows a real Android toolbar text field simultaneously with extra keys;
- places the terminal-session drawer at the logical end/right edge;
- provides two rows of Fold controls with session cycling, drawer, keyboard, `TEXTBAR`, navigation, PageUp, and PageDown;
- advertises synchronized-output support to tmux once the terminal emulator implements DEC mode 2026.

Properties remain available as recovery switches, but their defaults are the Fold choices rather than upstream's general-purpose choices. Release metadata is centralized in `app/build.gradle` and asserted by the read-only integration workflow so label/version drift fails CI.

## Shared contracts

Java equivalents of shared TypeScript types/schemas are used at the new boundaries:

- property names, accepted wire values, defaults, and parser registration live in `TermuxPropertyConstants`/`TermuxSharedProperties`;
- drawer placement crosses into UI code as the `TerminalSessionDrawerPosition` enum, not an unchecked string;
- Termux-specific extra-key actions live once in `ExtraKeysConstants` and are reused by the default profile, display map, dispatcher, and tests;
- Android view references use generated `R.id` resources;
- synchronized output uses one DEC mode bit and typed emulator/session methods;
- one immutable root Gradle profile supplies version, label, package variant, and ABI filters to every module;
- schema/default consistency, extra-key parsing, release metadata, and ARM64 targeting are CI assertions.

## Patch structure

Fixes stay isolated when that improves review, regression testing, or selective cherry-picking. This is a permanent personal appliance fork: upstream PRs and wholesale synchronization are not goals. The upstream branch is a reference for individually audited security or compatibility changes, not a target the Fold profile must continually rebase onto.

## Installation boundary

No generated APK is installed until the dedicated signing key, Proton Pass backup, encrypted Termux data backup, fresh bootstrap, rollback procedure, and physical Fold test plan are complete.
