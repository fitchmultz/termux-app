# Fold device profile

This is an opinionated personal fork of Termux with two priority device profiles:

- daily driver: Samsung Z Fold 8 (`SM-F971U1`), Android 17
- retained regression target: Samsung Z Fold 8 Ultra (`SM-F976U1`)
- ARM64, Samsung Keyboard, cover and inner displays, and resizable windows
- Pi, tmux, Node.js, Git, SSH, and localhost-service workflows

Other compatible devices and keyboards are welcome; there is no model allowlist. Layouts follow actual window dimensions and font scale rather than device names. The signed APK currently packages ARM64 only. Broader compatibility is best-effort, not a claim of physical validation. Historical Ultra results remain evidence for that device, not proof that a new build passes on either device.

Samsung's [Fold8 specifications](https://www.samsung.com/us/smartphones/galaxy-z-fold8/) describe a 5.5-inch, 10:16 cover screen and a 7.6-inch, 4:3 inner screen; the Ultra has different dimensions. Window transitions, Samsung keyboard composition, and split-screen/pop-up resizing matter more than model-specific layouts.

Release checks cover narrow/wide windows, keyboard-reduced height, font scaling, and the existing session/draft/rendering contracts. Physical checks on the daily driver and, when available, the Ultra remain explicit post-install gates.

See [FORK_CHANGES.md](FORK_CHANGES.md) for the canonical concise list of shipped and pending deviations. Every downstream behavior, default, release constraint, or operational-policy change must update that inventory in the same commit.

## Defaults

PR integration, signed APK publication, and physical validation are separate steps. Passing automated release checks does not establish physical Fold8 validation.

The integrated Fold profile:

- identifies itself as `Termux Fold` version `0.119.0-fold.10` with monotonic version code `2026102410`;
- builds and packages only `arm64-v8a` with the Android 7 bootstrap variant, requiring Android 7/API 24 or later;
- compiles against Android 17/API 37.2 using a Java 21 toolchain and Java 17 source/bytecode baseline, while retaining target SDK 28 for direct prefix execution;
- enables Samsung character-based terminal input;
- avoids automatically opening the keyboard on app return or existing-session navigation while retaining the keyboard controls; `show-soft-keyboard-on-navigation = true` restores the old automatic requests, subject to existing startup-hide and keyboard-disable settings;
- offers a collapsible native multiline Evidence Dock above the extra keys, with per-session drafts and private file staging;
- places the terminal-session drawer at the logical end/right edge;
- provides two rows of controls on narrow windows and one row when all twelve labels and 48dp touch targets fit; preserves custom key matrices and places Up before Down;
- combines the session title and readable Dock/Panes actions into one header when only one pane is visible;
- advertises the terminal emulator's implemented DEC mode 2026 synchronized-output support to tmux.

Properties remain available as recovery switches, but their defaults are the Fold choices rather than upstream's general-purpose choices. Release metadata is centralized in the root `build.gradle` and asserted by the read-only integration workflow so label/version drift fails CI.

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

## Offline shell tools

The Fold source profile embeds a locked native Android-7 ARM64 bootstrap
with Git, OpenSSH/SFTP, tmux, jq, ripgrep, fd, fzf, rsync, tree, zip, wget, zsh with
completions, lsd, bat, git-delta, direnv, git-lfs, and the termux-api client alongside
the upstream baseline. Fresh installations need no first-launch network install.
It does not replace or auto-upgrade a nonempty existing prefix. Existing users can
explicitly run `pkg update` followed by
`pkg install git openssh tmux jq ripgrep fd fzf rsync tree zip wget zsh zsh-completions lsd bat git-delta direnv git-lfs termux-api`.
Bash remains the default: no aliases, shell hooks, direnv/dotfile activation,
or global Git configuration changes are added. No services auto-start. Mise/Chezmoi,
Node/Python versioned development environments, compilers, Tcl/Tk, X11, and
the same-Fold-key Termux:API Android app remain separate. The client alone does
not enable clipboard/API access or grant Android permissions; only the permissions
needed for chosen APIs should be granted. See [delivery, trust, reproduction, and maintenance](tools/bootstrap/README.md).
Source integration is not APK publication or physical offline-bootstrap validation.

## Installation boundary

Installation remains owner-controlled. Verify the dedicated signing identity and its Proton Pass backup, an encrypted Termux data backup and recovery procedure, reviewed bootstrap evidence, and the physical Fold test plan before updating. Physical fresh-bootstrap completion and heap acceptance are not established by CI; updating a nonempty prefix does not exercise that path. Never replace a real prefix just to test it.

APK replacement can stop running Termux processes. Save work and wait for native builds and other critical jobs to finish before choosing **Update**; never uninstall to update. Android normally rejects a lower version code, so recovery is a same-key, higher-code roll-forward or an explicitly prepared backup/restore procedure, not an ordinary APK downgrade.
