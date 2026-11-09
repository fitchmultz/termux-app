# Fold upstream capability audit — 2026-11-09

## Scope and source identity

This audit starts from Fold16 `792d527882cbc85bbd8aa5c2de140d5e94fe4f29` and
Termux master `8629e632fcb95da272221be327db653fb24befe9`. The upstream merge base
is an audit boundary, not a request to rebase this appliance fork. All **17**
upstream-only commits were inspected semantically: ancestry alone does not show
which fixes the selective fork already implements. Earlier PR6 and dependency
and bootstrap audits are historical evidence, not today's selection policy.

The installed daily-driver APK is Fold16 (`0.119.0-fold.16`, `2026110516`),
not the default GitHub latest public Release, Fold10. The prior successful
[Fold16 signing run](https://github.com/fitchmultz/termux-app/actions/runs/42145474899)
corroborates the owner's installed-APK evidence: SHA-256
`b6e9b795ae3930702fb18192b223028ea99428af3f1476cc0e52496c8b1eb098`, certificate
`221f36cca6e32ee1666bb210046654b26900cd9ea98c2155d0cbe66102060437`.
All release pages and tags were inspected; Fold17 was unused at selection.

## Every upstream-only commit

| Upstream commit | Capability / decision |
| --- | --- |
| [`3b66f879`](https://github.com/termux/termux-app/commit/3b66f879) | **Already included.** RUN_COMMAND does not write file-based error results before external-app policy approval. Retain Android permission and Fold tests. |
| [`74ab5126`](https://github.com/termux/termux-app/commit/74ab5126) | **Already adapted, with broader lifecycle coverage.** Per-session scroll offset, activity/view/pane replacement, resizing and foreground refresh; retain Fold synchronized-output and selection/scroll-lock behavior. Do not replace it with the narrower upstream change. |
| [`47c473f9`](https://github.com/termux/termux-app/commit/47c473f9) | **Already included.** Right-edge erasure clears stale wrapped-line metadata, preserving copied newlines. |
| [`0a60d2df`](https://github.com/termux/termux-app/commit/0a60d2df) | **Inapplicable Android-5 fallback.** Fold's minimum is API24; `Canvas.drawTextRun` exists from API23. Do not add an unreachable older-platform branch or lower the supported floor. |
| [`1021b581`](https://github.com/termux/termux-app/commit/1021b581) | **Already semantically covered.** Cursor geometry already uses float operands (`cursorHeight`, `right`, `left`); changing `4.` to `4.f` does not recover lost integer precision here. |
| [`5532cc4f`](https://github.com/termux/termux-app/commit/5532cc4f) | **Already included.** UTF-16 row space uses `int`, with wide/combining-text regression coverage. |
| [`8b72226d`](https://github.com/termux/termux-app/commit/8b72226d) | **Already covered.** Clipboard Base64 flags are zero, exactly `Base64.DEFAULT`; retain Fold's deliberate payload-free error logging rather than upstream's data-bearing error message. |
| [`9adb09a5`](https://github.com/termux/termux-app/commit/9adb09a5) | **Not independently needed.** The image adaptation uses stricter checked Canvas/normal-heap bounds instead of spawning `getprop` to discover a much larger drawing ceiling. Do not add an unused duplicate system-property utility. |
| [`fd2ab4fc`](https://github.com/termux/termux-app/commit/fd2ab4fc) | **Selected remaining parser capability.** OSC/DCS infrastructure, image fast paths and prompt release of large control buffers; unsupported APC remains discard-only. Preserve Fold's existing ST-only DCS termination, 512KiB OSC52 allowance, 8KiB unrelated-control bound, overflow recovery and payload-free logs. |
| [`3b270134`](https://github.com/termux/termux-app/commit/3b270134) | **Selected.** Real Sixel DCS and iTerm OSC1337 single/multipart inline images for coding previews, with Android decoding and terminal-cell rendering. Adapt synchronized snapshots, memory accounting, identifiers, collection and surrounding text; see below. No file downloads, uploads or package installation. |
| [`d8d6b02a`](https://github.com/termux/termux-app/commit/d8d6b02a) | **Already exceeded by Fold.** Upstream's 100KiB clipboard bound must not reduce Fold's tested 512KiB parser allowance. |
| [`45844885`](https://github.com/termux/termux-app/commit/45844885) | **Already adapted.** Suppress fullscreen keyboard-margin prediction in split/pop-up windows; retain Fold's composition and sizing behavior. |
| [`b4a2c32c`](https://github.com/termux/termux-app/commit/b4a2c32c) | **Already included.** Root content-provider MIME queries no longer dereference a null filename; Fold also normalizes extensions independently of locale. |
| [`084d709f`](https://github.com/termux/termux-app/commit/084d709f) | **Already included.** JNI releases pair the correct original `jstring` with its UTF bytes. |
| [`7116605e`](https://github.com/termux/termux-app/commit/7116605e) | **No missing capability.** Fold already validates the wrapper in read-only PR/main checks and manually dispatched signed release. Do not restore an obsolete parallel upstream workflow. |
| [`b72e0208`](https://github.com/termux/termux-app/commit/b72e0208) | **Already superseded.** Fold pins Gradle Actions v6.4.0, newer than upstream's v6 wrapper validation. |
| [`8629e632`](https://github.com/termux/termux-app/commit/8629e632) | **Inapplicable workflow.** Fold deliberately has no dependency-submission/write-permission workflow; adding one would not improve the native environment. |

## Open upstream PR capability review

All **134** open app PRs were enumerated with pagination (100 + 34); titles and
scope were triaged, and relevant selected diffs were inspected. This is not a
claim that every line of every open proposal was reviewed or passed tests.
Unmerged candidates below are distinguished from master commits above.

| Open PR / inspected head | Decision and relevance |
| --- | --- |
| [#5375](https://github.com/termux/termux-app/pull/5375) `be82fc7288bf8eded5e3a0932ddab022dcb63207` | **Adapt.** Mixed image/text runs must flush text and preserve indices/selection state; use Fold's completed presentation owner for pixels, palette and cursor. |
| [#5403](https://github.com/termux/termux-app/pull/5403) `02c0f65359c4165f8b72a66f4edcb86a3249c6a7` | **Adapt.** Resolve OSC type at `;`, not `:`, retaining Fold's clipboard/default limits. |
| [#5424](https://github.com/termux/termux-app/pull/5424) `2f38925de3a4e2d984526835c1582ca52a0ddabb` | **Select.** Snapshot source styles as well as source text during overlapping same-row copying; prevents both text styling and bitmap-cell corruption. |
| [#5356](https://github.com/termux/termux-app/pull/5356) `40fa56b74141332d907dd261d04354ab0b7368aa` | **Select.** Horizontal-margin cell copying is not transcript row scrolling and must not move Fold selections/SCROLL offsets. Route image placement through the same accounting; LR copies do not count as transcript movement. |
| [#5325](https://github.com/termux/termux-app/pull/5325) `c8d0e4ea0b402c6432e32aa10c1d19b3f30a8777` | **Adapt.** Valid empty queue writes are no-ops, including control-only sanitized paste; retain closed/range validation and avoid addition overflow. |
| [#5230](https://github.com/termux/termux-app/pull/5230) `e2bd0ca26d43a10450d30482429e1ce7907b821d` | **Adapt.** Coalesce redundant PTY Looper wakeups; retain queue/read-buffer invariant, process exit and Fold watchdog early-return behavior. |
| [#5352](https://github.com/termux/termux-app/pull/5352) `ca7c4a817e3c4225e8b17a1163501ceeb1c76f89` | **Select.** Bound scroll-up count by scroll-region height, avoiding excessive work on malicious/extreme CSI input. |
| [#5353](https://github.com/termux/termux-app/pull/5353) `e73357fc89f40aa4e19710117cb0771c78cdb3e7` | **Select.** Reverse index above the scroll region moves the cursor without scrolling that region. |
| [#5354](https://github.com/termux/termux-app/pull/5354) `1e65e0fc3db17070bf4f453ea324e9879af41751` | **Select.** Cursor/margin correctness; retain absolute versus origin-relative coordinates. |
| [#5355](https://github.com/termux/termux-app/pull/5355) `9fb4ab6cd602fb53b15974d6f862f51adb9b8744` | **Select.** Clamp movement to the appropriate screen/margin boundary. |
| [#5357](https://github.com/termux/termux-app/pull/5357) `7dd6e3fb1baee13641b08cf129b0f949c0d0e3d7` | **Adapt.** Ignore DEL; CAN/SUB cancels controls without printing DEL, including the new image fast paths. |
| [#5378](https://github.com/termux/termux-app/pull/5378) `c2ead99f328824af002d011234b1a0dc4765b650` | **Select.** Wrapped-word selection crosses the visible/transcript boundary. |
| [#5295](https://github.com/termux/termux-app/pull/5295) `9e3bf41f5df1beddb7b88c637e1084c3273dd692` | **Adapt.** Unmatched Ctrl+Alt chords reach the active terminal pane; recognized Fold shortcuts still route normally. |
| [#5294](https://github.com/termux/termux-app/pull/5294) `aecb81e7b5e53f81904489272d6575d13261a0b2` | **Adapt.** JNI partial-allocation/string/local-reference cleanup and preserved pending exceptions, with checked PID output and PTY failure paths. |
| [#5410](https://github.com/termux/termux-app/pull/5410) `75fbcdbab7f944fd86464ff785b73745803c3256` | **Do not duplicate.** Newer proposal is less complete than #5294: unchecked duplication and continued acquisition after failed cwd conversion. |
| [#5246](https://github.com/termux/termux-app/pull/5246) `ffcda3bf28e276c5c0d008d71b7c71aaaeafb13e` | **Select only client-FD and AM single-result corrections.** Do not import unconditional process killing: unresolved PID lookup can return `-1`. Main shared-source changes do not update the pinned API companion. |
| [#5238](https://github.com/termux/termux-app/pull/5238) | **Do not wholesale import its stale graphics/Kitty stack.** Independently adapt invalid decode-bounds rejection (`9add03d0`), final-column cursor correction (`13318246`), and overlapping renderer fix. Kitty is a distinct unmerged protocol, not a prerequisite for Sixel/iTerm. Its Fold resize report informs the explicit placement invalidation policy below. |
| #5265, #5264, #5345 | **Already covered.** Simultaneous real Android input/extra keys, confirmed rename/exit menu, and UTF-16 overflow regression exist in Fold with lifecycle protections. |
| #5312, #5387, #5391 | **Do not replace current policy.** Fold already has SCROLL behavior and readable theme-aware controls; persistent scroll indicator or terminal-palette-linked toolbar is separate UX, not a missing fix. |
| [#5366](https://github.com/termux/termux-app/pull/5366) `a9f40a5b813146091b41d0a130043bd0740beff8` | **Selected in a separate input change.** Pi requests Kitty flags 7 and distinguishes Enter/Shift+Enter; repair literal IME controls so synthetic Ctrl+M is not misencoded. Preserve active-pane routing, shortcut precedence, reset/screens and press/release lifecycle. |
| [#5287](https://github.com/termux/termux-app/pull/5287) `1f74c023a20bdcdfdddf9ce7773e3e89bbb02a40` | **Adapt separately.** Honeyboard arrow suppression occurs before DPAD delivery. Virtual surrounding-text metadata must belong to the current terminal InputConnection, never real editor text or stale callbacks. |
| [#5242](https://github.com/termux/termux-app/pull/5242) `851315d0525483321f6eaa11c7aefe27f43390d7` | **Adapt opt-in direct-terminal preedit separately.** It adds real CJK composition beyond the existing editor; reconcile its actual composing selection with Honeyboard's virtual model, gate the entire alternate behavior, and use completed presentation state. Raw head does not preserve these Fold contracts. |
| [#5256](https://github.com/termux/termux-app/pull/5256) `4738aef079cd460e41df7ec97554e1624c7717cf` | **Do not globally remap modified Enter.** It collapses Shift/Ctrl/Ctrl+Shift identities and drops Alt in combinations for every legacy shell/TUI. Negotiated Kitty provides Pi's newline capability without that semantic loss; Ctrl+J remains available. |
| [#5390](https://github.com/termux/termux-app/pull/5390) `6fb4ee65d468c21882fa27b9305e622c6a2f8905` | **Adapt separately as a usable OSC8 capability.** Pi emits hidden Markdown destinations and wrapped file targets. Metadata alone is invisible: integrate existing explicit Android URL actions, ASCII writes and immutable completed-frame resolution; preserve mouse-reporting ownership and safe target inspection. |
| [#5316](https://github.com/termux/termux-app/pull/5316) `6ec101ab65948bfc31c05775771f2bb4d9a92292` | **Adapt the real Chafa subset, not the raw decoder.** Validate strip/tag/sample bounds before allocation, decode at requested sampling and convert unassociated RGBA correctly. Preserve native DNG handling; do not claim general TIFF support. Pi's ordinary raster route does not require TIFF, but Chafa iTerm output does. |
| [#5409](https://github.com/termux/termux-app/pull/5409) | **Separate source review in progress.** Evaluate actual large-input/backpressure and process-lifecycle behavior rather than excluding an asynchronous writer merely for lack of tests. Final source-based decision will be recorded before release. |
| [#5223](https://github.com/termux/termux-app/pull/5223) `7a046b64833a7e95242e4bea0e2e0cefdcad46db` | **Retain audited alternative.** Fold's pinned `login → bash -l → /etc/profile → profile.d` path reaches second stage; HUSHLOGIN suppresses only the banner. This is not installer-synchronous and retains its failure lock. Do not import prefix deletion/uninstall recovery. Fresh-prefix physical acceptance remains separate. |

New gesture, automatic IME and separate-Android-task proposals are not silently
substituted for the current native panes/composition/window contract. No claim of
upstream maintainer approval or passing upstream tests is inferred from a PR title.

## Inline-image integration decisions

- Enable the upstream capability normally, not behind a device/model allowlist or
  an arbitrary feature toggle. Sixel is advertised in Primary Device Attributes;
  iTerm `File`, `MultipartFile`, `FilePart`, `FileEnd` and cell-size reporting use
  the terminal parser. Kitty graphics is a different protocol, not a claimed alias.
- Keep Samsung composition, bracketed paste, toolbar/editor, end/right drawer,
  independent panes, Evidence Dock, palettes and DEC2026 output presentation.
  Rendering must use the selected completed-screen buffer for **both** cells and
  bitmap lookup. Snapshots retain only visible completed images, share immutable
  pixels, and never hold parser-in-progress images or the entire transcript.
- Fix upstream image/text-run bookkeeping, unsigned bitmap identifier and
  coordinate encoding, live/history image collection and attribute operations.
  Image eviction must not recycle pixels still referenced by a completed frame.
- Validate dimensions with checked wide arithmetic and actual Android bitmap
  limits, bound encoded multipart input and decoded/retained pixel memory, and
  reject malformed data without logging private image/clipboard payloads.
  Keep the selected upstream Android draw-byte defaults: 100MiB before Android 15,
  150MiB from Android 15, and Canvas's 32,766-pixel software axis bound. Exact
  Android/upstream source links are in `TerminalBitmap.java`; these are draw limits,
  not heap allowances. Retain upstream's encoded-data maximum cumulatively across
  multipart input. The earlier candidate-only 16/64MiB heap-fraction policies,
  weak global registry/reservations and per-buffer eviction quota were removed:
  they had no established product/platform source. Ordinary allocator failures are
  caught and rejected; no app-total/native-RSS guarantee or `largeHeap` is claimed.
  Margin-aware placement and completion must not scroll an unrelated DECSTBM/DECLRMM
  region or double-count transcript movement.
- Live image placements are invalidated on column or cell-pixel geometry changes
  rather than reflowing image slices as `+` text. Ordinary text/history/cursor
  behavior remains; completed synchronized snapshots retain their old pixels.
  Row-only window changes retain the existing row movement behavior. Programs may
  redraw previews after a fold/font change. Cursor/selection does not recolor
  image pixels; surrounding text keeps the existing cursor/selection policy.
- Treat actual native-render fixture captures as **synthetic test content rendered
  by production Android code**, not Fold8 screenshots or physical validation.
  The exact same baseline-compatible fixture runs against unchanged Fold16 and
  the candidate. Protocol and pixel assertions supplement visual inspection.

## Actual Pi image route, not automatic Termux discovery

At the inspected public Pi pin
[`cd74c5a3615635c61e68c52922debcda7ed53b51`](https://github.com/fitchmultz/pi/commit/cd74c5a3615635c61e68c52922debcda7ed53b51),
`packages/tui/src/terminal-image.ts` does not recognize a Termux identity. Unknown
terminals and tmux default to no images. Its native `PI_IMAGE_PROTOCOL=iterm2`
override selects the existing iTerm emitter; no ITERM/TERM_PROGRAM spoof is needed.
The actual CLI accepts `--tui-mode regular`; fullscreen deliberately disables iTerm
images. With the existing `terminal.showImages` enabled (default true), launch:

```sh
PI_IMAGE_PROTOCOL=iterm2 pi --tui-mode regular
```

This is a per-invocation route, not a shared settings/dotfile change. It assumes
`terminal.images` is unset/`"auto"`; an explicit `"kitty"` or false setting overrides
capability detection, and `terminal.showImages:false` still wins. No one-shot
show-images override exists at this pin, so this task changes neither setting.
Use a fresh ordinary invocation: restored working-session mode is applied after
the CLI/default mode. In compact view, expand the relevant tool output to display
its images. The public pin is research provenance, not proof of the installed
phone revision.

The unchanged production module was actually executed with existing Node24 on the
M1. Default unknown/tmux detection returned null; the native override selected
iTerm, including under the simulated tmux environment. `renderImage()` emitted the
75,193-byte test fixture (SHA-256
`f0d3895a147106b072d7110a534486d737383141aa36991157d008613a51296e`).
`app/src/test/resources/pi-emitter-proof.json` records source blob/hash, environment
results and geometry. Native Android parser/renderer tests consume those exact
bytes, rather than substituting a hand-written protocol model. This is not a full
interactive Pi/phone execution or proof of tmux forwarding.

Test direct Termux first. An environment override cannot make tmux forward an
unsupported image protocol; tmux/passthrough/version behavior remains separate.
No Node/package/adapter or global Pi setting is modified. A simple existing
coreutils route for a local PNG is also available after the app update:

```sh
printf '\033]1337;File=inline=1:'
base64 -w 0 ./preview.png
printf '\a'
```

## Bootstrap, companion and runtime boundaries

None of the 17 upstream-only app commits changes bootstrap installer/archive,
native bootstrap build inputs or default packages. Upstream app still uses its
older generic 2026.02.12 Android-7 archive; Fold has a separately authenticated,
locked 121-package October snapshot and identified lsof backport. Retain that
reviewed input closure, its offline/reproducibility checks and its executable
manifest. This is not a claim that each native package is today's latest.

An APK update returns early for a nonempty prefix. It neither upgrades existing
libraries nor fixes Node's ICU linkage. Runtime adapters, Node, dotfiles, services
and package upgrades belong to the coordinator's separate task. No new image
viewer package is silently installed: compatible user tools may emit Sixel or
OSC1337 directly. The bundled termux-api client is not the Android companion.
Companion source is pinned and built separately, not vendored into the app.
Its API source `6c8eaa223bcc387e6b28146d340acb20712aa3e7` and shared producer
`7bceab88e2272f961d1b94ef736f1a9e20173247` remain unchanged. The producer also
contains the two socket patterns fixed here, but changing main does not change
that reconstruction; a companion fix requires its own reviewed source pin,
provenance and reproducibility update, not a silent production-source overlay.

## Decision log and delivery gates

1. Reconciled live default/main, all release/tag pages, installed identity and
   prior signed-run provenance; selected increasing Fold17 metadata.
2. Re-read existing audits and classified every master delta semantically before
   selecting the missing image/parser capability. No wholesale rebase, SDK/target,
   dependency or native-package refresh.
3. Adapt upstream image primitives rather than invent a second graphics protocol
   engine. Add real Android protocol/bitmap/rendering and snapshot regressions;
   retain the existing Fold behavior suite.
4. Run before/after native-render fixtures and complete exact-head read-only Fold
   checks on the M1 Linux Actions runners. Independent Pi Durable **and** Cursor
   review, current base/head, and addressed findings are required before merge.
5. After merge, require the actual merged-main check; create a new annotated
   Fold17 tag, dispatch `Fold signed release` from `fold/main` with
   `publish_release=true`. Require two byte-identical unsigned builds and the
   fresh environment-scoped signing job. Inspect the actual published APK,
   checksums, provenance, signatures, identity, API/ABI and ZIP alignment.
6. Leave installation/restart and physical acceptance to Mitch/coordinator.
   APK replacement may stop Termux processes: save work, choose **Update**, never
   uninstall or replace the real prefix for a test. No physical result is inferred
   from CI or the synthetic native renders.

Correctness now has its independent [PR36](https://github.com/fitchmultz/termux-app/pull/36)
including locked-input transport recovery; it is not held behind graphics. Input,
OSC8 and Chafa adaptations have separate implementation/review scopes. No capability
is represented as merged merely because it is selected above.

Final PR/run/release evidence belongs in the PR delivery record, not invented
success assertions in this source audit.
