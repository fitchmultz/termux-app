# Responsive terminal input — selective part5/8

## Source and unchanged release contract

Adapts black1771's [upstream PR5409](https://github.com/termux/termux-app/pull/5409),
head `1b5b0f11f238086fbf3a556e72c297f9a6498195`, not its constructor-started
worker/job-queue architecture. Its exact product policy is
[`ByblackUnifiedInput.java:9`](https://github.com/black1771/termux-app/blob/1b5b0f11f238086fbf3a556e72c297f9a6498195/terminal-emulator/src/main/java/com/termux/terminal/ByblackUnifiedInput.java#L9):
`MAX_PENDING = 4 * 1024 * 1024`. The Fold adaptation uses a fixed preallocated
**4-MiB per-session byte ring**, not a global heap/RSS or paste-staging guarantee.
Caller Strings, sanitization/bracketing, UTF-8 staging, writer and kernel buffers
are outside that number; their CPU work is not moved off the UI thread.

Preserves package/prefix/private signing identity, ARM64-only profile, min SDK24,
target SDK28, current release metadata, completed-frame DEC2026 presentation,
Fold shortcuts/editor/panes/drawer and all prior composing/Honeyboard lifecycle
repairs. The integration starts from actual CJK main
`fe59e7042b2b2dfc4c5c31827caac4b05199a345`; only the frozen incremental input
hunks were applied, not older whole-file snapshots.

## Input ownership and failure behavior

- `ByteQueue.tryWrite()` validates ranges with overflow-safe subtraction, then
  copies the entire event into the ring or nothing. Valid empty writes remain
  successful no-ops; output-reader blocking queue behavior is unchanged.
- The existing session PTY writer drains the same FIFO. Accepted keys, explicit
  pastes and terminal replies keep admission order, without another worker,
  executor or per-job retained byte arrays.
- Existing ESC/C1 paste sanitization and CRLF/LF normalization precede brackets
  and UTF-8 encoding. Explicit/emulator paste is admitted as one event, so FULL
  cannot leave an orphan prefix/body/suffix. Empty unbracketed sanitized paste
  stays a no-op; empty bracketed paste retains its framing.
- `tryWrite(String)`, `tryWrite(byte[],off,count)` and `tryWriteCodePoint()` return
  **FIFO admission**, not delivery/PTY-consumption, receipts. Existing void APIs
  delegate; TerminalOutput's final String API is unchanged. The UTF-8 scratch
  remains serialized until the queue has copied it.
- FULL/CLOSED claims coalesce with admission under the same lock. Callbacks run
  outside locks on the session Handler; Activity feedback identifies the original
  session and says input was not sent. No rejected payload is put into a new
  callback/toast/log. Background/library clients receive a callback/reason log
  and can provide their own UI.
- Never-started, initializing, failed and closed sessions reject nonempty input.
  No process or worker starts in the constructor. The existing waiter starts
  before native resources and waits for setup's finally-release; only a returned
  positive child PID is handed to it. Post-return setup failure attempts SIGKILL
  and queue/fd cleanup despite secondary failure, preserving the original error.
  Healthy transport remains **three existing threads**. No synchronous setup-
  thread waitFor remains; native pre-return cleanup/wait semantics are unchanged.

## Keyboard and composing compatibility

A release-eligible physical DOWN is admitted only together with a promise of
capacity for its future UP **inside the same existing ring**. Ordinary admission
leaves that capacity untouched; matching UP appends once through its original
session-owned token, in FIFO order, even while ordinary input remains FULL. This
is actual ring-space ownership, not another payload queue or a heap/RSS budget.

UP retains live modifiers. The bound is measured from the same encoder with all
supported modifier bits, not a guessed allowance or merely the DOWN-time release
length: functional modifier maximum **208**, text **16** plus captured Shift
alternates, derived by unchanged [KeyHandler.kittySequence()](https://github.com/fitchmultz/termux-app/blob/756632f35dea15710f526c31c24e780c7d37d1c2/terminal-emulator/src/main/java/com/termux/terminal/KeyHandler.java#L249-L263) and its [text-lock mask](https://github.com/fitchmultz/termux-app/blob/756632f35dea15710f526c31c24e780c7d37d1c2/terminal-emulator/src/main/java/com/termux/terminal/KeyHandler.java#L214-L232). Near-full input that fits DOWN but not that bound rejects DOWN atomically.

Only an admitted initial physical DOWN owns a future UP promise. Repeat bytes
retain their existing encoding/admission behavior; they cannot create or revive
release identity after initial rejection or mode/screen/session cancellation.
Matching accepted repeats keep the initial holder; failed repeats do not lose
its reserved release. Existing client/Fn/action precedence, no-event-reporting,
plain-text and recovery-key behavior remain unchanged. Flag2 legacy Ctrl+I reserves
only together with its actual initial TAB admission. FULL never falls back or
retries legacy bytes. The retained
`KittyKeyboardInputTest.repeatsDoNotCreateOrRevivePendingReleases` is unchanged.

UP/cancellation spends or returns capacity idempotently. Session/screen/protocol
changes, focus/window loss, detach, canceled or intercepted events cannot send a
stale release into a new context. A valid physical press survives an IME-only
connection replacement. Client code-point/Fn/app-action precedence and literal
C0-from-IME behavior remain unchanged. All newer guarded preference, session/
generation, stale-legacy closure and collapsed-span repairs are preserved.

**Not transactional:** existing unbracketed IME/CJK finish paths may admit a
prefix of per-codepoint events before FULL. Editable/modifier state is not rolled
back or automatically retried, and IME acknowledgement is not an admission
receipt. Termination or I/O failure may discard accepted pending bytes; a failed
writer closes admission and reports CLOSED. The change does not guarantee
termination after an OS-denied signal, successful native reaping or pre-return
partial-child cleanup.

## Qualification, not invented execution

The frozen adaptation authors31 methods: ByteQueueAdmissionTest6;
TerminalSessionWriteTest17; TerminalInputRejectionTest1; TerminalImeAdmissionTest1;
KittyKeyboardAdmissionTest6. The25 Robolectric methods target SDK28/37;
graphics-facing fixtures explicitly select native graphics. The six emulator
methods use existing JUnit. Test preparation/source review is
not Java/native/device execution; consult exact-head CI and actual XML/logs.
Existing Kitty/Honeyboard/CJK/DEC2026/pane regressions remain required. No
production class finality, test-only transport fallback or SDK dependency changes
were used to appease mocks.

A separately source-reviewed `PiKeyboardWorkflowCaptureTest` adds actual production
TerminalView/real InputConnection → session queue capture on SDK28/37:15 ordered
records, seven synthetic physical DOWN/UP pairs plus a real IME LF commit. Its
strict JSON has exactly `schema, androidSourceBase, sdk, kittyFlags, records`.
`androidSourceBase = 00c0896ec49aaf4e3fc62ab41bc71cbff3c75440` declares the
producer's baseline, **not execution-head attestation**; exact run/head/artifact
provenance must be retained separately. It uses a synthetic positive-PID/no-PTY
fixture with the production queue, not a child process or kernel transport.

After actual JSON exists, feed DOWN/IME bytes to the independently staged,
unchanged public Pi Editor at pin
`cd74c5a3615635c61e68c52922debcda7ed53b51`. Validate UP records but exclude them
from direct Editor input; this does not execute TUI release filtering. Syntax/
staging alone is not consumption proof. This is component qualification, not
a provider/full Pi CLI/physical keyboard/tmux workflow or shared settings change.

No phone installation/restart or runtime/package/prefix/dotfile/shared Pi setting
change is performed. Physical slow-reader/paste/IME/heap acceptance remains for
owner-controlled installation; it is not an invented release blocker.

## Cursor backpressure finding and evidence repair

Cursor finding [4512414866](https://github.com/fitchmultz/termux-app/pull/40#discussion_r4512414866)
is substantive: the original nonblocking integration admitted a short DOWN,
removed its identity on UP, then ignored a FULL rejection of the longer release.
The follow-up repairs the shared queue/session/physical-event paths rather than
removing release reporting, adding a worker, blocking UI input or weakening tests.
The later first-admitted-repeat addition was incorrect: Pi finding4513053471
traced it against the retained no-create/no-revive regression. This correction
removes that addition, keeps the retained test unchanged, and removes only the
new tests that encoded the contradictory policy. Matching initial-press capacity
and full-through-UP regression coverage remain intact.

Seventeen additional regression methods are authored: eleven real Session/FIFO
physical-event methods, three Session admission/lifecycle methods and three queue
methods. They keep ordinary capacity full through UP, test modifier growth,
multiple owners/refunds, cancellation and IC rebinding. Existing methods remain.
These authored additions require fresh-head execution; source review is not a pass.

Both original `756632f...` runs42611397157 and42611541651 executed531 cases
without failures/errors/skips, including all31 initial regression methods and both
SDK28/37 capture variants. Those results **did not detect the UP defect**. Their
report artifacts contained zero JSON because the upload pattern selected only
PNG/XML. No raw JSON was reconstructed and no Editor-consumption proof exists
from those runs. The narrow added `pi-input-sdk-*.json` upload path retains the
original generated captures on the new head; separate run/head/source/artifact
binding and the unchanged pinned Editor probe remain required.
