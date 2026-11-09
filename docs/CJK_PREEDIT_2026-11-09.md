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
