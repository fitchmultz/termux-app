# Fold device profile

This is an opinionated personal fork of Termux with two priority device profiles:

- daily driver: Samsung Z Fold 8 (`SM-F971U1`), Android 17
- retained regression target: Samsung Z Fold 8 Ultra (`SM-F976U1`)
- ARM64, Samsung Keyboard, cover and inner displays, and resizable windows
- Pi, tmux, Node.js, Git, SSH, and localhost-service workflows

Other compatible devices and keyboards are welcome; there is no model allowlist. Layouts follow actual window dimensions and font scale rather than device names. The signed APK currently packages ARM64 only. Broader compatibility is best-effort, not a claim of physical validation. Historical Ultra results remain evidence for that device, not proof that a new build passes on either device.

Release checks cover narrow/wide windows, keyboard-reduced height, font scaling, and the existing session/draft/rendering contracts. Physical checks on the daily driver and, when available, the Ultra remain explicit post-install gates.

See [FORK_CHANGES.md](FORK_CHANGES.md) for the canonical concise list of shipped and pending deviations. Every downstream behavior, default, release constraint, or operational-policy change must update that inventory in the same commit.

## Defaults

The integrated Fold profile:

- identifies itself as `Termux Fold` version `0.119.0-fold.8` with monotonic version code `2026091708`;
- builds and packages only `arm64-v8a` with the Android 7 bootstrap variant;
- enables Samsung character-based terminal input;
- offers a collapsible native multiline Evidence Dock above the extra keys, with per-session drafts and private file staging;
- places the terminal-session drawer at the logical end/right edge;
- provides two rows of controls on narrow windows and one row when all twelve labels and 48dp touch targets fit; preserves custom key matrices and places Up before Down;
- combines the session title and readable Dock/Panes actions into one header when only one pane is visible;
- advertises synchronized-output support to tmux once the terminal emulator implements DEC mode 2026.

Properties remain available as recovery switches, but their defaults are the Fold choices rather than upstream's general-purpose choices. Release metadata is centralized in `app/build.gradle` and asserted by the read-only integration workflow so label/version drift fails CI.

Native split-session controls, Evidence Dock behavior, limits, and physical acceptance checks are documented in [FOLD_WORKBENCH.md](FOLD_WORKBENCH.md).

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
