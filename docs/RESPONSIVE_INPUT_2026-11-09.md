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

Remember Kitty release identity only after the actual press event is admitted;
FULL remains consumed without falling back/retrying legacy bytes. Flag2 legacy
Ctrl+I tracks only after its actual final TAB write. Accepted press identity
survives rejected repeats. Client code-point/Fn/app-action precedence and literal
C0-from-IME behavior remain unchanged. Preserve all newer guarded preference,
window/session/generation, stale-legacy closure and collapsed-span fixes.

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
