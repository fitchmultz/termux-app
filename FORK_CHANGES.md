# Downstream changes

This is the canonical concise inventory of intentional differences from the audited Termux upstream base. Update it in the same commit whenever a fix, feature, default, release constraint, or operational policy changes.

## Scope and release profile

- Prioritizes the owner's Z Fold 8 (`SM-F971U1`, Android 17), retains the Ultra (`SM-F976U1`) as a regression target, and welcomes other compatible devices without model-name gating. The signed APK remains ARM64-only.
- Keeps package ID `com.termux` and prefix `/data/data/com.termux/files/usr`; this is an in-place fork, not a side-by-side package.
- Uses label `Termux Fold`; this branch's source release profile is `0.119.0-fold.18` (`2026111018`). PR integration, signed publication, and physical validation are separate steps.
- Packages only `arm64-v8a` with the `apt-android-7` bootstrap; split release APKs are disabled.
- Retains target SDK 28 for direct execution of programs from the writable Termux prefix. Modern targets require routing execution through Android's system linker plus a separately patched package ecosystem; forced-linker tests currently break Node test workers and `age-keygen`. Android 17 may therefore show an old-app or **Install anyway** warning.
- Uses a dedicated Android signing identity. Signing material must never be committed or made public, but protected GitHub Actions secrets and signed GitHub Releases are allowed when deliberately configured.

## Fixes

- This source branch retires each run-command forwarding service start after handoff or rejection, including null restarts. Removing its foreground notification no longer leaves an idle started service behind.

- This source branch makes Help own and destroy its WebView, removing it from the view hierarchy first and ignoring page callbacks after destruction. Both the loading and displayed states release the browser's native resources.

- This source branch makes settings preference stores belong to their fragment instead of process-wide singletons. Closing or recreating Settings no longer leaves its first Activity retained by every visited preference page; companion-app error dialogs keep their Activity context.

- This source branch closes file-receiver inputs after saving, validation/copy failure, dialog dismissal, or activity destruction; the pending dialog owns each opened input until saving consumes it.

- This source branch keeps a large report's serialized backing file across activity configuration recreation, so the replacement can reload its contents. Final dismissal still deletes the file, and the existing age-based cleanup removes abandoned files.

- Source branch `audit-fix/appshell-review`: failed or cancelled background commands close their owned stdout/stderr streams before reader joins, so an inherited writer cannot hold Android cancellation delivery open; successful commands still drain to EOF before publishing results.

- This source branch keeps background-command results with the worker until the child exits and both output readers finish, including cancellation and stdin-write failure. Cancellation no longer publishes still-changing output or loses its exit status to late worker initialization.

- This source branch closes background-command stdin before waiting, including when no input was supplied, so commands that read until EOF can finish.

- Source branch `audit-fix/extra-keys`: Runs extra-key repeats on the main looper and cancels pending presses on reload or detach. Removed modifiers lose their latches and locks; popups are released on cancellation, dismissal, reload, and detach.

- Source branch `audit-fix/terminal`: session changes, view detach and framework action-mode destruction tear selection down immediately; only gesture dismissal retains the short grace period.

- Source branch `audit-fix/terminal`: retained terminal screens, images, partial transfers and synchronized frames share a stable callback handle, so replacing the session client releases the previous activity. A null client retains the existing Android logging fallback.

- This source branch admits each delivered file-receiver intent once across pause/resume and owns its name dialog explicitly. A new intent retires the previous prompt/input before presenting its own; the existing shared text-input API keeps its behavior.

- This source branch resumes the evidence share-target chooser after backgrounding and resolves its selected session at confirmation time; ended or removed targets refresh the choices without losing the pending share or reattaching an untracked session.

- Source branch candidate: the service owns terminal completion policy and requested removal across Activity replacement, including detached plugin results; the Activity only updates panes and exit notices. Unexpected nonzero exits remain available for inspection.

- Gives the bell the earliest time the next bell may sound plus whether a follow-up is already posted, instead of one timestamp that had to mean both, the file receiver's name dialog an explicit `FINISH_ACTIVITY`/`KEEP_ACTIVITY` dismissal, and the installer a `BootstrapState` read from the prefix directory instead of nested existence checks. The keyboard-margin workaround's two debounce timestamps are named for the adjustment each guards, with the 40 ms interval as a constant.

- Gives image placement one record instead of an "open" boolean beside its margins and scroll action, so the three cannot get out of step, and an open OSC 8 hyperlink has a `NO_HYPERLINK` value instead of a bare `0`. The pending line wrap is named for what it is.

- Gives the stream gobbler a `RUNNING`/`SUSPENDED` state and one claim on its close notification, the local socket server a `STOPPED`/`RUNNING`/`CLOSED` lifecycle, and the AM socket server a `SETUP_NOT_RUN`/`ENABLED`/`DISABLED` value instead of a nullable `Boolean`. A local socket manager now reports running only after its server starts, and starting a closed server returns an explicit error instead of leaving a server that reports running with no listener.

- Gives text selection a named lifecycle (`HIDDEN`, `JUST_SHOWN`, `ACTIVE`). The 300 ms guard against a hide that follows a show is part of the state instead of an age check on a bare boolean, and the action mode is documented as owned for exactly the active interval.

- Gives the split layout one intent value (`SINGLE`, `DUAL`, `DUAL_COMPACT_FORCED`) and a three-value measured width class instead of two booleans plus a nullable `Boolean` that also meant "not measured yet". The file picker in flight is one record, the editor's draft-suppression guard and the consumed creation intent have names that say what they mean, and the evidence store's import has an explicit `IDLE`/`RUNNING` state alongside the preference that survives process death.

- Gives the activity and service one explicit lifecycle each. The activity is `INVALID`, `CREATED` or `STARTED` instead of two booleans, so "must not run" and "in the foreground" cannot disagree. The service records why it is stopping (`USER` or `NOTHING_LEFT`), holds its two wake locks as one value, and gets one owner for command placement: `TermuxShellManager.trackPending/trackSession/trackTask` move a command out of the pending list in the same step that starts it. A session's removal request is consumed once by its finished callback. `wantsToStop()` now reports the empty-service stop as well, which previously left the activity to clean up sessions the service had already killed.

- Gives the keyboard controls explicit states. A special button is `UNCREATED`, `INACTIVE`, `ACTIVE` or `LOCKED` instead of three booleans, with tap, long-hold and read-and-release as named transitions. The extra-key press is one object that cancels its repeat or pending lock and remembers whether it already acted, instead of a count and two nullable jobs. The soft-keyboard policy owns its one shared show request plus the four reasons a show is withheld (returning, programmatic focus, startup-hidden, delayed enable). The two virtual volume-key modifiers remain two independent latches, which is their real shape.

- Gives five small view machines one owner each: kitty key presses that await their release, the cursor blinker (off, or posting toggles at a valid rate), the mouse-scroll gesture anchor (one record instead of two coordinates plus a time sentinel), the waiting dead-key accent (named "no accent" instead of a bare `0`), and the one in-flight AutoFill request. Session attachment keeps its deliberate two-phase form (session first, emulator once sized) and now says so where it is implemented. Removes the unused `mCursorInvisibleIgnoreOnce` field.

- Gives the IME connection one owner with two states, `ABSENT` and `BOUND`. Invalidating, adopting a new connection and queueing a restart are now `ImeConnection` transitions, and the generation it bumps is the single answer to "is this deferred work still current?". Behaviour of the composing editor, the Honeyboard virtual editor, deferred rebinding after focus loss and legacy draft cancellation is unchanged.

- Gives synchronized output one frame object instead of a mode bit plus two deferred-callback flags. The frame owns the frozen renderer snapshot, the deferred cursor callback and the pending palette delivery, so "active" cannot disagree with what the renderer sees. The session keeps only its watchdog.

- Gives UTF-8 decoding and OSC recognition one explicit state each. `Utf8Decoder` holds either no bytes or one incomplete sequence, so partial input can no longer be mistaken for a decoded character. The OSC string is `AWAITING_TYPE`, `UNKNOWN`, `TYPED` or `ITERM_IMAGE`, with the image phase replacing the two fast-path flags.

- Gives the DCS parser one explicit phase (`DETECTING`, `SIXEL`, `OTHER`) instead of two booleans that always moved together. A separate flag names the pending `ESC` that may complete the string terminator.

- Gives the APC parser and image draining one explicit state each. An APC string is now in `TYPE`, `IGNORED`, `HEADER` or `PAYLOAD`, instead of three booleans. A rejected control string records `NONE`, `OSC`, `DCS` or `APC` plus whether an ESC is pending, so draining can no longer be confused with a real escape state.

- Gives a terminal session one explicit shell lifecycle (`CREATED`, `STARTING`, `RUNNING`, `EXITED`, `FAILED`, `CANCELLED`) instead of a pid that also encoded progress plus two flags. Input admission, the waiter handshake, failure handling and `isRunning()` now read that state. A session that never started no longer sends `SIGKILL` to pid `0`, which targets the app's own process group, and finishing it records `CANCELLED`, closes its queues, records exit status 137, and delivers the finished callback once. Later sizing cannot start the cancelled shell, and a missing transcript is not returned as the text "null". The input-rejection episode carries one named state instead of a nullable reason.

- Gives a queue capacity reservation one explicit state (`AVAILABLE`, `HELD`, `SPENT`, `CANCELLED`) instead of two flags. Only a held promise owns reserved bytes, so spending or cancelling twice still cannot return capacity twice.

- Gives each execution command one explicit lifecycle: a single result-delivery claim replaces the public `processingResultsAlreadyCalled` flag, failure records its error and state together, and `canBeKilled()` states whether a kill request can still reach the command's process.

- Kills a background task's process when the service stops. Recording the kill failure previously moved the command past its executing state, so the task was reported failed while its process kept running; terminal sessions were already killed unconditionally.

- Groups Fold checks by source repository and branch, so push and PR runs cancel superseded work together while preserving branch/main checks and keeping fork branches independent.

- Kitty physical DOWN admission atomically protects encoder-bounded future UP capacity inside the existing input ring. Matching releases consume their original session-owned promise once even while ordinary input remains FULL; only an admitted initial DOWN owns a future release; ordinary repeat bytes remain unchanged and cannot create or revive release ownership after initial rejection or context cancellation. Context/lifecycle cancellation returns capacity without stale delivery, while IME-only rebinding retains valid physical releases. CI preserves the synthetic keyboard-capture JSON in addition to PNG/XML reports.

- Geometry-invalidating resize clears APC parser arguments only while actually aborting an APC. The shared OSC/DCS argument buffer survives unrelated resize, retaining OSC52 clipboard data and OSC8/title/color/query prefixes; active Kitty APCs still drain and between-command pending uploads still abort. Native API28/37 and parser regressions verify these contracts.

- Isolates a Kitty source-pixel crop before filtered scaling, after requested/padded geometry admission. Android's fast filtered source-rectangle draw otherwise samples pixels outside the crop; the native SDK28/37 regression reproduced `0xff1def0f` instead of a selected pure-green pixel. The shared PNG/RGB/RGBA path preserves filtering quality, cached original pixels and immutable published ownership; regressions require the entire green fit and transparent padding, not a relaxed color threshold.

- Kitty `C=1` clips an anchored placement at the bottom before publication, rather than scrolling text inside/full-width margins while merely preserving cursor coordinates. Pinned Kitty `screen.c` scrolls only after cursor movement; regressions retain sentinels above the placement and verify cursor/transcript counters.

- Ordinary wide-text insertion and generated blanks remove obsolete bitmap payloads before renderer/geometry discard can ghost an image or erase text. Valid existing text attributes stay intact; recursive cleanup restores the actual incoming image slice and snapshots collateral styles before mutation.

- Adds a test-only, baseline-compatible native Kitty preview fixture for unchanged before/after captures at 480/900 widths. Graphics bytes are generated by executing pinned production Pi `terminal-image.ts` (`cd74c5a…`) with the existing PNG; source/input/output hashes and a dependency-free regeneration script document upload, cropped cache placement, soft deletion and reuse. Captures exercise synthetic Android rendering, not physical-device acceptance.

- Keeps bitmap payload out of incidental wide-character cleanup: image placement preserves neighboring text style (or NORMAL for stale bitmap payload), exact target slices and margins across File/Multipart/Sixel shared row paths. Already-classified Sixel data is not reclassified as DECRQSS/termcap merely because a reused chunk starts `$q`/`+q`; genuine requests and invalid-image draining retain their existing behavior.

- Composing preview distinguishes packed bitmap-cell metadata from text style before underline/color decoding, using the existing default text style and completed-frame palette/reverse-video snapshot for image cells. This prevents palette-index crashes and invisible preedit after cursor positioning over an image; ordinary colored text-cell behavior is unchanged. Native SDK28/37 regressions include bitmap slots0/300, coordinate-derived invalid text indices and synchronized-frame isolation.

- Replaces the remaining image-margin test subclass of final TerminalBuffer with composition around the existing production placement/callback API. Production finality is preserved; no test-only seam or allocator quota is added.

- Keeps bitmap-map field, constructor and import consistent after removing insertion-order eviction, fixing the genuine Cursor compile finding without restoring the rejected quota.

- Test-only composing-preference and finished-session Activity fixtures complete the first real PRIVATE preferences load before dual-mode construction and verify backend identity. The Activity case also checks immediate enabled readback and the real Activity's enabled preference before rebinding; default-OFF and immediate true/false preference coverage remain intact. No production preference I/O, fake backend, seeded setting, or post-setter idle is introduced.

- Reconciles opt-in composing preference changes even after an OFF connection was invalidated, with guarded/coalesced restart requests and stale legacy-draft cancellation only for admitted ON replacement. Collapsed/clipped composing regions finalize retained drafts once instead of hiding text ahead of terminal navigation/deletion; ordinary OFF behavior remains unchanged.

- After an observed window-focus loss, request guarded IMM rebinding for an invalidated composing/Honeyboard connection on actual focused return, including missed-gain reattachment. Keep generation/session/selection cancellation and native-editor/ordinary-legacy isolation; do not request keyboard showing or replay canceled drafts. API24 input fixtures use explicit synthetic legacy layout spacing, not production font clamps or rendering claims.

- Requires a valid nonempty composing span before ON-mode deletion edits the local Editable; absent/empty markers retain terminal Backspace fallback. Natural empty/equal AOSP operations are expected to be unmarked, not a claimed zero-span reproducer. The retained zero-marker regression is separately synthetic; OFF and active supplementary-text behavior are preserved.

- Restarts focused Honeyboard virtual editors, or opt-in real composing editors, after session navigation, carrying intent across temporary null/rapid replacements and guarding deferred work by connection generation, selection and focus. Clears stale Honeyboard drafts before BaseInputConnection closure; OFF-mode ordinary IME closure remains unchanged. Regression dispatch is explicitly a test-local asynchronous direct-target IMM bridge, not full system/ViewRoot emulation.

- Preserves available legacy initial functional-key presses for events-only Kitty flags 2/6, including DECCKM/SS3 and F1-F4, while retaining layout-resolved Space, legacy-null fallbacks, repeat/release encoding and Pi flags7. This is a Termux compatibility policy, not a native Kitty parity claim. Corrects the synthetic keymap fixtures to pinned Robolectric 4.17's actual char-valued native hook without production seams.

- Wrapped-word lookup checks adjacent row boundaries in linear work instead of repeatedly serializing the growing transcript span; long wrapped-line regressions exercise the public final buffer directly in both directions without test-only inheritance or a timing policy.
- Selectively adapts source-confirmed open upstream fixes for valid empty/sanitized writes, coalesced PTY input wakeups, overlapping cell styles, cursor/scroll-margin/cancellation correctness, wrapped transcript words and unmatched Ctrl+Alt active-pane routing. Existing Fold shortcuts, synchronized-output timeout and queue behavior remain; focused regressions cover those contracts. See the [correctness source map](docs/UPSTREAM_CORRECTNESS_2026-11-09.md).
- Adapts fuller JNI partial-allocation/UTF/local-reference/PTY failure cleanup, preserving pending exceptions and validating PID output, plus connected-client-FD and single AM-error-result repairs. Does not import unsafe unconditional process killing or change the independently pinned API companion producer.

- This source branch batches ordinary printable ASCII into row-bounded runs instead of repeating decoding, width lookup, and cell setup for every byte. UTF-8, escapes, line drawing, insert mode, wrapping, cursor normalization, and complex Unicode rows retain their existing paths.
- This source branch schedules terminal flings on Android's animation frames instead of immediately reposting the polling loop. It retains the existing scrolling physics, history bounds, mouse reporting, and pane/session cancellation.
- Routes multiline IME commits through the existing paste path when bracketed paste is enabled, while keeping ordinary text, single Enter, and unbracketed terminal input unchanged.
- Adapts upstream `74ab5126` to retain each session's scroll offset across session/pane replacement, activity-view restoration, keyboard/window resizing, and foreground refresh, bounded by remaining history. SCROLL reconciles background/detached output once; cancelled evicted selections follow live output unless scroll-locked. All view refreshes defer unfinished synchronized frames, while normal new output still follows the terminal.

- Ports upstream `47c473f9` to clear stale wrapped-line metadata when an erase reaches the right edge, preserving newlines in copied terminal text.
- Ports upstream `5532cc4f` to keep UTF-16 row lengths in an `int`, preventing overflow with supplementary combining text in wide terminal rows.
- Selectively adapts upstream `fd2ab4fc` string-framing corrections: only `ESC \` terminates a DCS, and OSC escape-overflow cannot resume an aborted sequence. Retains existing parser limits and does not add image protocols.

- Selectively incorporates upstream `45844885`, `b4a2c32c`, and `084d709f`: prevents fullscreen keyboard-margin predictions from resizing split-screen/pop-up windows, handles root file-share MIME queries, and pairs JNI string releases with the correct arguments. Also normalizes MIME extensions independently of the user's locale in sharing and document-provider paths.

- Keeps Compose and Split readable in both themes at the bottom of the session drawer. The compact shared header shows the session title without single-pane focus decoration; split panes retain separate focus headers.

- Includes upstream `3b66f87`: rejects file-based RUN_COMMAND error results until the external-app policy has passed; Android's RUN_COMMAND permission remains required.

- Removes a session from the drawer after its confirmed long-press exit, while retaining unexpectedly failed sessions for inspection.
- Preserves the Android 11+ all-files/storage-permission setup path used by the previously installed F-Droid beta.
- Implements DEC synchronized output mode 2026, including DECSET/DECRST, DECRQM, deferred text/color/cursor presentation, reset/process cleanup, and cursor-blink suppression.
- Adds a two-second synchronized-output watchdog so a malformed or terminated application cannot freeze rendering indefinitely.
- Retains a bounded immutable copy of the last completed visible frame while synchronized output is active, preventing Samsung Pop-up View from drawing partial PTY updates. Each pane's Android background uses that same completed palette, including when another pane changes colors. Pane changes transfer keyboard focus and cancel stale gestures; the screen-awake setting applies to both views.
- Preserves simultaneous text-field visibility and unsent text through activity recreation, including when the row is hidden.
- Routes drawer layout, Back handling, hardware shortcuts, and the `DRAWER` extra key through one logical start/end position contract.

## Features

- Starts each regular file in `~/.termux/boot` as a background task, in name order, after boot and after an APK update. This replaces the Termux:Boot plugin, whose public builds cannot share this fork's signing identity. A script that keeps running holds the foreground service without a session or wake lock. See [boot scripts](docs/ANDROID_BACKGROUND.md#start-programs-at-boot).

- Adapts upstream PR5390 OSC 8 cell metadata for labeled links, including the Fold ASCII fast path, Unicode/reflow/history, and immutable synchronized-frame targets. With the existing transcript-click setting enabled, tapping a label opens its URI through Android's existing URL handler; ordinary detected URLs and disabled-click behavior remain unchanged.
- Uses the renderer's completed-screen scroll clamp for link taps, so a scrollback offset during DEC 2026 cannot redirect a visible label or ordinary URL to another row.

- Adapts upstream PR5409 into session-owned nonblocking, all-or-none input admission with the exact source-backed 4-MiB byte-ring capacity, one existing FIFO writer and admission receipts. Explicit/emulator paste is one sanitized/framed event; per-codepoint IME/CJK commits are not made transactional. FULL/CLOSED rejection is visible for active Activity clients without logging payloads. Positive-child gated waiter/setup cleanup retains three healthy transport threads and no constructor-started worker. See [source, ownership and execution limits](docs/RESPONSIVE_INPUT_2026-11-09.md).

- Adapts upstream PR5238's direct Kitty PNG/RGB/RGBA frontend to the checked common bitmap backend, with unsigned IDs, source-pixel crop, cached placement/delete/reuse, margin-aware scrolling and immutable completed-frame ownership. No filesystem/shared-memory transfer, identity spoof, feature gate or new quota. See [source and scope](docs/KITTY_GRAPHICS_2026-11-09.md).

- Adds the narrowly validated Chafa-style single-strip LE RGBA8 TIFF fallback for iTerm output, with native BitmapFactory first, pre-allocation strip/layout validation, direct sampling and alpha-correct row conversion. This is not general TIFF support, and native DNG preservation is source-reviewed rather than backed by a new DNG fixture. See [the source/provenance and acceptance limits](docs/CHAFA_TIFF_2026-11-09.md).

- Selectively integrates upstream Sixel and iTerm single/multipart inline images while preserving Fold OSC52 limits, completed DEC2026 pixel ownership, mixed text runs, panes/input and scroll margins. Uses sourced Android/upstream draw and encoded-data limits, checked geometry, bounds-first decoding and immutable publication—not custom heap-fraction quotas or a native-RSS guarantee. Column/font-cell changes clear only live fixed-geometry images so programs can redraw; completed frames retain old pixels. No image files are downloaded, uploaded or executed. See the [master/open-PR source audit and real Pi launch route](docs/UPSTREAM_AUDIT_2026-11-09.md).

- Adds default-OFF direct-terminal IME Composing Preview with real spans/selection, mutually exclusive Samsung metadata, completed-frame cursor/palette/style rendering and connection/session guards. Uncommitted enabled preedit is canceled—not replayed—on invalidation; existing clients default false and the real editor stays untouched. See [source, safety policy and verification limits](docs/CJK_PREEDIT_2026-11-09.md).

- Adds connection-scoped Samsung Honeyboard navigation metadata so its empty-editor arrow suppression can be addressed without inserting sentinels into terminal/editor text. Deferred updates require the current focused connection/session/generation and are invalidated on replacement, close or lifecycle changes. See [source and physical-acceptance limits](docs/HONEYBOARD_INPUT_2026-11-09.md).

- Adds negotiated Kitty keyboard flags1|2|4 for distinct Pi Enter/newline and press/repeat/release input while preserving legacy mode, Fold shortcuts/panes/editor and literal IME control bytes. Main/alternate stacks retain PR5366's sourced 32-entry policy. See [source, IME repair and verification limits](docs/KITTY_KEYBOARD_2026-11-09.md).

- Adds a native control editor in the session drawer and Terminal I/O settings. Drag or tap to reorder, move between rows, add keys/macros, remove keys, and reset. Saved app layouts override the file matrix explicitly; returning to the file layout leaves termux.properties untouched. Editor drafts survive activity recreation. Oversized app layouts scroll horizontally without shrinking touch targets. The default plus key starts a session through the existing new-session path; Home remains available in the editor.
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

- This source branch follows the app's light/dark mode for the terminal palette when no `~/.termux/colors.properties` exists. Light mode uses Catppuccin Latte and dark mode retains the existing Termux palette. Custom color files keep their existing behavior. Palette changes update all sessions and notify subscribed terminal programs; indexed color queries report the active palette.
- This source branch keeps the soft keyboard hidden on return from another app and does not open it merely to switch existing sessions. It cancels pending show requests when the keyboard is hidden and clears a carried-over IME on the returning window, while retaining existing status-bar inset handling. Initial launch, new sessions, and deliberate keyboard controls remain available; set `show-soft-keyboard-on-navigation = true` in `~/.termux/termux.properties` and run `termux-reload-settings` to restore the old automatic requests. Existing startup-hide and keyboard-disable settings still take precedence.
- Enables Samsung character-based terminal input for immediate command typing.
- Makes the Evidence Dock available alongside extra keys, initially collapsed, and places the session drawer at logical `end` (right in this profile).
- Uses two stable, compact right-aligned rows: `CTRL/PREV`, `ALT/NEXT`, `UP/PGUP`, `ESC`, `TAB`, `DRAWER`; then `NEW_SESSION`, `LEFT`, `DOWN/PGDN`, `RIGHT`, `NEXT`, `KEYBOARD/TEXTBAR`. Width follows rendered labels and 48dp minimum targets. Custom matrices retain their rows and full-width layout. Vector Sessions/Keyboard icons and accessibility descriptions replace tiny glyphs. Resizing preserves button instances, modifiers, macros, and popups.
- Starts shells with the termux-tools `TERMUX_HUSHLOGIN=1` switch, without editing shell files or the packaged banner. Help remains in the terminal context menu; `env -u TERMUX_HUSHLOGIN login` displays the banner unless `~/.hushlogin` also suppresses it.
- Keeps explicit keyboard controls because full-screen TUIs such as Pi use terminal mouse tracking and consume terminal taps.

## Contracts, tests, and repository policy

- Kitty acceptance consumes the exact pinned production Pi stream through Android, checks full/cropped cached placement geometry after delete/reuse, and requires non-background preview pixels from the unchanged native capture fixture. The baseline fixture retains only baseline-compatible assertions; no parser/renderer substitution or test seam is added. This is synthetic Android proof, not a physical-device result.

- Inline-image pixel/Canvas assertions run only on Robolectric-supported native graphics API28/37. An explicit API24 LEGACY case tests parser recovery and cell-size replies without allocating/rendering bitmaps; production minimum SDK24 is unchanged.

- Honeyboard API24 input coverage uses a method-scoped legacy Paint layout-spacing shim because Robolectric 4.17 leaves font spacing zero. It preserves the attached/focused InputConnection/IMM/PTY-queue test path and assertions without runtime clamps, forced renderer fields or font/pixel-fidelity claims; native API28/37 is unchanged.

- The negotiated-input drawer regression advances the real DrawerLayout animation in its manually laid-out Robolectric fixture instead of assuming looper idling supplies draw traversals. Shortcut dispatch, pane/release and terminal-byte assertions stay intact; no runtime drawer behavior changes.

- Uses the same fixed public release-asset API fallback for the exact pinned custom lsof URL/SHA pair after browser transport failure. This repairs a separately observed HTTP504 after successful snapshot recovery; common checksum/offline/cache fail-closed behavior and all package/content pins remain unchanged.

- Qualifies image rendering with identical before/after native Android fixtures and captured bytes from the unchanged pinned production Pi emitter. Captures are synthetic test content rendered by production code, not physical Fold screenshots. The documented per-invocation regular-mode/iTerm route needs no terminal-identity spoof or shared Pi configuration change; tmux transport and physical acceptance remain explicit limits.

- Adds included-source JNI host fault injection with fake JNI/PTY/fork boundaries and tracked allocations, and focused Java socket wrapper/framing regressions. The read-only job provisions GCC/libc headers; Android NDK compilation remains separate. Host/shadow tests do not prove ART, real child lifecycle or socket execution.
- If a content-addressed bootstrap snapshot's browser download fails, tries its exact public same-repository REST asset; all archive/member/profile/cache hashes and input pins remain required. Integrity failures never trigger alternate downloads. Bootstrap failure-path fixtures use the same existing authenticated snapshot recovery as production; no package or prefix contents change.

- This source branch combines wrapper validation, full tests, metadata and one validated unsigned release APK in PR and main checks, reusing Gradle state between runs. Signed release compares a successful exact-tag main APK with a clean build; if they differ, it requires two matching clean release-job builds instead. Bootstrap reproducibility runs for bootstrap changes; unrelated inventory changes do not trigger the independently pinned Termux:API companion.
- Documents the observed Android child-process restriction and optional temporary-file retention for background work. These are device/user settings, not APK defaults or guarantees; see [background-session guidance](docs/ANDROID_BACKGROUND.md).

- Provides a [pinned-source Termux:API companion workflow](docs/TERMUX_API_COMPANION.md): two independent exact-source library→local-Maven→API release chains, narrow publication/resolution-only overlays, strict metadata/APK/provenance/reproducibility gates, and default-off, main-branch-only isolated signing with the existing Fold key. Explicit publication-repository receivers avoid Groovy module-name shadowing; the source audit selects external raw AARs without changing production project variants. CLT 23 skips only the obsolete `--licenses` handshake, not package-license handling. Short-lived artifacts only; workflow integration does not publish/install the companion or establish physical clipboard access. Signing, distribution, installation/hold clearance and permissions remain separate owner-controlled steps.

- Retains the unused Fold 6 tag after cancelling publication before signing to fix a cross-pane synchronized-background regression; release tags are not rewritten.

- Defines one immutable root Gradle profile for version, label, bootstrap variant, and ABI filters across modules.
- Extends property schemas and tests so keys, accepted values, defaults, parsers, and UI consumers cannot silently drift.
- Tests the Fold extra-key grammar, storage policy, drawer enum/default, simultaneous-input defaults, synchronized-output behavior, and bounded large OSC 52 clipboard writes.
- Uses `fold/main` as the GitHub default; `master` remains an audited reference point, not an automatic synchronization target. Fork CLI/API operations explicitly name the intended repository rather than relying on upstream-prone inference.
- Treats this as a permanent personal appliance fork. Upstream PRs and wholesale rebases/syncs are not goals; relevant security or compatibility fixes are selectively reviewed and cherry-picked.
- Keeps `Fold checks` read-only for wrapper validation, full unit tests and packaged metadata/ABI/signature checks. Synthetic workbench render images and unit-test reports are retained briefly as CI artifacts; no private terminal contents are captured.
- Runs every Actions job on a GitHub-hosted `ubuntu-24.04` x64 runner, inside a digest-pinned Ubuntu 24.04 container on a VM that serves only that job. Tools provisioned explicitly in the container, per-command exact-checkout Git ownership exceptions, verified GitHub CLI 2.103.2 in signers, and companion runner/container provenance support the build/signing gates. See [runner policy](docs/CI_RUNNER.md).
- Provides a manually dispatched `Fold signed release` workflow: an unprivileged job verifies two byte-identical builds of the matching annotated tag, then a separate environment-scoped job (with no reviewer/wait gate) signs and verifies; untagged main changes are not signed. This branch's artifact-only dispatch saves the verified signed APK/checksum/provenance for authenticated download with repository retention; public Release publication remains a separate explicit boolean gate. Inherited upstream artifact/release/dependency/JitPack workflows are removed.

- Keeps installation owner-controlled with signer/backup/recovery and physical-test readiness. Checkpoint running work before APK replacement; never uninstall to update or assume Android accepts lower-code APK rollback. See [installation boundary](FOLD_PROFILE.md#installation-boundary).

## Offline default tools

- Backports lsof's eventpoll crash fix into a separately identified Fold package for fresh prefixes. The original signed Termux package remains locked as the base; the derived package has its own reviewed source, build, and digest. Existing prefixes require an explicit package update; installing the APK does not alter them.
- Replaces the single upstream bootstrap download with a native Android-7 ARM64 package snapshot: Git, OpenSSH/SFTP, tmux, jq, ripgrep, fd, fzf, rsync, tree, zip, wget, zsh/completions, lsd, bat, git-delta, direnv, git-lfs, and chezmoi join the existing baseline, available offline on fresh installation. Versioning, signed publication, and physical installation remain separate release steps.
- Authenticates maintenance indices with the pinned Termux Releases public key; locks every package URL/version/architecture/SHA-256 and the upstream generator sources. Build-private generation verifies all inputs and package/control extraction paths, uses only a local snapshot, canonicalizes the compressed archive, validates dpkg files/checksums plus production ELF/API/library closure, and publishes atomically. Adds libmount/libblkid for an undeclared inherited util-linux runtime dependency; keeps optional upstream installed-test fixtures without bundling their compiler/ASan runtimes.
- This branch recovers unavailable inputs for recorded locks from a content-addressed public archive, verifies the archive and every member against the reviewed lock/profile, and retains the builder's cache checks and offline behavior. Tagged signing restores matching inputs before tests and after clean without changing the tagged source or existing app releases.
- Keeps nonempty existing prefixes untouched by APK updates; documents explicit `pkg install` migration and separate Node/Python versioned development and Termux:API Android companion boundaries. Bash remains the default, without aliases, direnv hooks/allow, user-dotfile activation, or global Git changes. Neither sshd nor ssh-agent auto-starts. Ordinary on-device offline postinst creates SSH host keys and delta completions, and registers bat's pager alternative below less; no keys are built into the APK.
- Adds the locked `termux-api` 0.60.0 client to fresh-prefix defaults with canonical clipboard get/set delivery contracts; the current closure contains 121 packages, including chezmoi. CI compares two clean ZIPs, the second offline; payload/ELF and production Java executable delivery checks invoke no API or clipboard operation. The client has no maintainer scripts/startup hooks; the same-Fold-key Android companion and only needed permissions remain separate. Existing prefixes/configuration are unchanged; client-only delivery does not establish working clipboard access.
- Adds runner/security and real-payload negative controls plus a two-generation/offline bootstrap CI gate alongside full unit, identity/API/ABI/signature, reproducible unsigned APK, and isolated signing checks. Physical offline bootstrap/heap/device checks remain explicitly unperformed until authorized installation. See [bootstrap maintenance](tools/bootstrap/README.md).
- Tracks the bootstrap ZIP as a prerequisite of its native assembly object, preventing stale embedded tools after a no-clean lock refresh; tests actual NDK A→B object bytes without source/makefile changes.
- Preserves package owner-execute bits through a generated executable manifest and a focused production staging extractor, instead of granting execute by path prefix. Real script/Git-hook execution tests isolate host Git/configuration and protect service scripts and hook templates without broadening ordinary files or enabling services; the nonempty-prefix early return and symlink/rename commit path remain.
- Initializes the existing screen-awake regression's backing preference before its dual-mode wrapper, avoiding Android-28 cold-loader races. The test asserts its initial state and retains real both-pane/persisted toggles; no runtime preference or view policy changes.
- Removes redundant snapshot/cache path restrictions while retaining the authenticated member set and hashes.

## Dependency and build maintenance

- Unit tests can use job-private, SHA-256-verified Robolectric native-runtime and Android 7/9/17 runtime seeds; unavailable artifacts use the normal resolvers and mismatches fail. Other versions, companion source rounds, reproducible builds, and isolated signers remain unchanged.

- Hosted CLT 23 setup skips the removed `sdkmanager --licenses` handshake, whose immediate exit can crash the setup action's stdin writer with `EPIPE`; actual SDK package installation and its license handling remain required in CI and isolated signing.

- Explicitly authorizes frequent proactive dependency/tool/bootstrap/default-package/action maintenance and useful stable-release API/convention adoption, without bypassing compatibility, read-only CI, isolated signing, reproducibility, backup/recovery, or owner-controlled integration/release gates.
- Uses AGP 9.4.1's public DSL, Gradle 9.8.2 with verified distribution/wrapper checksums, compile SDK 37.2 via the public minor-release DSL, build-tools 37.0.0, NDK r30, and explicitly provisioned stable command-line tools 23.0 in CI. Retains target 28, minimum 24, ARM64, and package identity.
- Sets all four modules' Java source/bytecode baseline to 17 with an explicit Java 21 compiler/test toolchain and current stable 21.x CI resolution. Supported AGP/D8 and core-library desugaring 2.1.5 retain the Android API floor; CI tests include Android 7/17 regressions and the required Java 21 FileDescriptor export for Robolectric's Android 17 bridge.
- Records all 19 direct Maven coordinates and tool/action decisions in the dated [dependency audit](docs/DEPENDENCY_AUDIT.md); those libraries were already current stable at that audit. The locked native package snapshot and its separate maintenance process are documented in [bootstrap maintenance](tools/bootstrap/README.md).
- Removes obsolete library-publishing/JitPack configuration and unused library ProGuard files, duplicate app Markdown dependencies and the now-transitive Guava conflict placeholder, and an inherited instrumented package-name template test. Guava remains an API dependency because shared property schemas expose its map types; sharing helpers use AndroidX's nullable annotation.
- Pins GitHub Actions by commit and explicitly provisions the SDK. Isolated signing retains all signature/reproducibility gates, including build-tools 37's per-scheme certificate output and a verifier-only API 23 floor for v1. The README distinguishes Fold-signed updates from upstream reference guidance.
