# Fold device profile

This is an opinionated personal fork of Termux for one deployment target:

- Samsung `SM-F976U1`
- Android 17
- ARM64
- Samsung Keyboard
- narrow cover display and unfolded inner display
- Pi, tmux, Node.js, Git, SSH, and localhost-service workflows

Compatibility with other devices, Android releases, keyboards, architectures, package variants, or user preferences is not a release criterion. Other people may use the public source, but downstream decisions optimize this device first.

## Defaults

The integrated Fold profile:

- identifies itself as `Termux Fold` version `0.119.0-fold.1` with monotonic version code `2026082001`;
- enables Samsung character-based terminal input;
- shows a real Android toolbar text field simultaneously with extra keys;
- places the terminal-session drawer at the logical end/right edge;
- provides two rows of Fold controls with session cycling, drawer, keyboard, `TEXTBAR`, navigation, PageUp, and PageDown;
- advertises synchronized-output support to tmux once the terminal emulator implements DEC mode 2026.

Properties remain available as recovery switches, but their defaults are the Fold choices rather than upstream's general-purpose choices. Release metadata is centralized in `app/build.gradle` and asserted by the read-only integration workflow so label/version drift fails CI.

## Patch structure

Generic fixes stay isolated when that makes review, testing, or upstream rebasing safer. The final integration branch adds this device profile explicitly. That separation is maintenance hygiene, not a commitment to support generic installations.

## Installation boundary

No generated APK is installed until the dedicated signing key, Proton Pass backup, encrypted Termux data backup, fresh bootstrap, rollback procedure, and physical Fold test plan are complete.
