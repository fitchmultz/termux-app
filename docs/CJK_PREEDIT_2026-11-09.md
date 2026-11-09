# Opt-in direct-terminal CJK preedit

Derived from sng2c <sng2nara@gmail.com>, open upstream
[PR5242](https://github.com/termux/termux-app/pull/5242), head
`851315d0525483321f6eaa11c7aefe27f43390d7`, base
`3df69d1da197dd9bd71a3bafd902dffd720576b4`. Settings/overlay wiring is adapted;
Fold lifecycle and composing-state integration is substantially downstream.

## Useful capability

Settings → Termux → Terminal I/O → **IME Composing Preview** adds a single-line
preview at the terminal cursor for uncommitted CJK text. It is **default OFF**.
The existing independent real native EditText/toolbar/EvidenceDock remains unchanged
and useful for long drafts. No actual phone preference is changed by this task.

OFF retains prior input-type/commit/finish behavior. Non-Honeyboard legacy
connections do not acquire session-attachment restarts. The optional client
capability defaults false so existing embedders need not implement a new method.
ON uses real Editable spans, Selection, surrounding text and extracted metadata,
not Samsung's virtual sentinel model. The two models are mutually exclusive.

## Composition and presentation contracts

- Normal commits/finishes and terminal-delivered bypass navigation commit current
  preedit once. Modifier keys alone do not finalize it.
- **Uncommitted ON preedit is canceled when its connection is invalidated**, never
  replayed into another pane/session. Stale/closed/background callbacks cannot
  write into the replacement session.
- Restart intent survives temporary-null/rapid replacements, applies only to the
  final focused destination and cannot discard a newer same-connection preedit.
- Stale Honeyboard closure clears its old Editable before Base closure; restarting
  metadata cannot flush an old draft into a new session. Native-editor focus and
  composing text remain independent.
- Code-point deletion outside active preedit falls back to terminal Backspace;
  actual composing deletion follows Editable state, including supplementary text.
- Overlay cursor, palette and style come from `TerminalRendererState`, including
  completed DEC2026 presentation state and its top-row clamp—not pending live
  terminal cells. This does not add a second renderer for terminal content.

This is not arbitrary Vietnamese replaceText/delta synchronization or a replacement
for the native multiline draft editor. The later responsive-input change makes
explicit/emulator paste atomic; it does not promise every per-codepoint IME commit
is an atomic admission transaction under saturation.

## Verification and evidence limits

The initial twelve authored functional composing-test methods plus a native OFF/ON capture case cover OFF behavior, actual spans/selection,
Honeyboard exclusion, flags1/7 controls/paste, deletion, stale/replaced/closed
connections, same-connection newer preedit, null/rapid replacements, real-editor
focus, preference refresh and completed-frame bitmap equality. An activity test
covers actual finished-session removal/rebinding. Native graphics cases target
SDK28/37; API24 is explicitly legacy, not a native-graphics claim.

Synthetic candidate OFF/ON native captures can document the visible difference;
they are not physical Fold screenshots or an unchanged-Fold16 build comparison.
Authoring/static review is not execution. Exact-head M1 CI and genuine final-pair
Pi Durable/Cursor reviews remain required. Actual Samsung/CJK/physical behavior is
unverified until owner acceptance tomorrow, not an invented release-blocking gate.
No Node/runtime, package, shared Pi/dotfile, SDK/profile, certificate or prefix
change is introduced here; the ongoing phone source build remains untouched.

## Empty-marker review correction and provenance

Both ON deletion APIs now redirect to the Editable only for a valid **nonempty**
composing span, consistent with the preview's definition of preedit. Absent or
zero-length markers use terminal Backspace; active codepoint deletion preserves
supplementary characters, and OFF behavior is unchanged.

Source tracing does not substantiate the review's claimed natural zero-marker
reproducer: AOSP's ordinary empty `setComposingText` and equal-endpoint
`setComposingRegion` operations are expected to leave **absent (-1/-1)** markers.
The new tests explicitly assert that distinction. A separate, clearly labelled
synthetic fixture retains the actual framework COMPOSING marker at 0..0 using
inclusive flags to exercise the defensive fallback; it is not an observed IME
sequence or physical-device reproduction. See the [InputConnection contracts](https://developer.android.com/reference/android/view/inputmethod/InputConnection)
and [AOSP SpannableStringBuilder](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/text/SpannableStringBuilder.java).

Five additional methods cover both deletion APIs, the synthetic marker, API24
legacy execution, supplementary composing deletion and OFF preservation. They
were authored, not executed at commit preparation. As in the Honeyboard fixture,
the documented test-only IMM bridge posts direct-target events asynchronously;
it does not emulate full ViewRoot/served-view routing or fabricate PTY bytes.

Keyboard fixture corrections and Honeyboard lifecycle regressions are integrated.
The merged production View matches the independently reviewed CJK correction
apart from one explanatory restart comment; composing-or-Honeyboard restart
intent and stale-draft guards retain their existing superset behavior. Real
final-head CI and both independent reviews remain required.


## Window-focus recovery correction and actual runtime limits

A non-IME window such as the expanded notification shade can take window focus
without changing Android's served IME view. The prior focus-loss handler
invalidated the feature InputConnection, but there was no true-focus-gain
restart: Android could keep the stale served connection and composing-mode
input would be rejected indefinitely. This is addressed by retaining known-loss
intent and requesting IMM restart only for an invalidated composing/Honeyboard
connection on actual selected/focused return or eligible reattachment.

The queued request rechecks generation, current connection, exact session,
attachment, view focus, known-loss window focus and selected-terminal status.
Null/rapid session replacements preserve intent while obsolete work is rejected.
Native editor and ordinary legacy connections do not acquire new restart policy;
canceled drafts are not replayed. `restartInput()` is not a keyboard-show request.

New tests drive `ActivityController.windowFocusChanged()` so real AttachInfo
window-focus state changes, rather than manually invoking a View callback. They
cover ON/Honeyboard recovery, missed-gain detach/reattach, null/rapid replacements,
stale callbacks and editor/pane/legacy negatives. The recording IMM establishes
**restart requests**, not complete served-view rebinding, notification-shade
behavior or physical keyboard-visibility acceptance.

At prior head `4c5c77499a36d1070de723f209f2795182ac0d22`, M1
[run42589920309](https://github.com/fitchmultz/termux-app/actions/runs/42589920309)
executed243 app cases:239 passed,4 failed,zero errors/skips. Failures were the
inherited drawer assertion and three API24 cases (one Honeyboard, two CJK)
that failed in common layout setup on zero Robolectric LEGACY font line
spacing. Their actual test bodies and IMM assertions were **not reached**.
Existing native API28/37 passes are not execution evidence for the new focus
regressions.

The inherited drawer/Honeyboard corrections are integrated. All three CJK
minimum-SDK methods—including the new focus-recovery case—now select an
API24-only Paint shim supplying synthetic layout spacing from the configured
text size, retaining `NoPty`, `RecordingImm`, LEGACY graphics, actual layout,
all test bodies and queue assertions. Native API28/37 configuration is unchanged.
The shim is not font or pixel fidelity, and production font policy is unchanged.
Both increments are independently source-reviewed; new-head full CI and fresh
Pi Durable/Cursor reviews remain mandatory.
