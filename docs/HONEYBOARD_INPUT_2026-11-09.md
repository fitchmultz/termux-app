# Connection-scoped Samsung arrow metadata

Adapts Sparito181 <sparitodtn@gmail.com>, open upstream
[PR5287](https://github.com/termux/termux-app/pull/5287), exact head
`1f74c023a20bdcdfdddf9ce7773e3e89bbb02a40`, compared base
`3b66f8799635a4dba4a206563048ff0e6792c487`.

## Useful change and scope

Samsung Honeyboard/Keys Cafe can suppress arrow emission before Android DPAD
reaches the app when the terminal advertises an empty editor. The upstream virtual
`x|x` surrounding-text context provides navigation metadata, not terminal text.
The downstream adaptation captures that policy for the current focused terminal
InputConnection and checks its connection/session/generation before deferred IMM
updates. Replacement, close, attach, detachment and focus loss invalidate stale
updates. Sentinels never enter the real Editable or PTY.

Other IMEs and Fold's real native editor/toolbar keep ordinary context and existing
Samsung input flags. No global IME, keyboard, dotfile, Pi or device setting is
changed. The later opt-in CJK preview selects real composing metadata instead of
these sentinels; these are distinct capabilities, not interchangeable workarounds.

## Verification boundaries

The initial five source test methods cover Honeyboard versus other IMEs, actual arrows,
virtual context, stale connections and real-editor separation. The fixture matches
production focusability. Its native graphics cases use SDK28/37; API24 coverage is
explicitly legacy, not native graphics. Authoring/independent static reread and
patch/tree checks do not establish execution.

Exact-head M1 CI and independent Pi Durable/Cursor reviews are required. Current
Samsung One UI/Keys Cafe emission, hardware keyboards and physical Fold behavior
remain unverified until owner acceptance; they are not invented release-blocking
gates. The phone's ongoing source build must not be interrupted.

## Review correction and test boundary

The served editor can stay focused during a session switch, so invalidation alone
was insufficient. Restart intent now survives temporary null/rapid replacement;
only the final attached, selected, focused Honeyboard target can request
`restartInput`. It never requests keyboard showing or steals native-editor focus.
Closing an obsolete Honeyboard editor clears its draft before Base closure;
ordinary other-IME closing behavior is preserved.

Four additional lifecycle methods and the extended deletion cases were authored,
not executed at this commit's preparation. Robolectric does not supply Android's
hidden IMM key-dispatch bridge. The fixture therefore explicitly posts events to
the supplied target view, preserving asynchronous timing and real TerminalView
byte production. It does **not** model served-view fallback or full ViewRoot/system
routing; no PTY bytes are fabricated. Actual final-head M1 checks and independent
reviews remain required before merge, and physical Honeyboard acceptance is not
claimed.


## Actual eda3 execution and minimum-SDK fixture repair

The M1 run [42589073268](https://github.com/fitchmultz/termux-app/actions/runs/42589073268)
at `eda3dcf3e694097728612a5ec72ea1f38f7299b1` executed208 app cases with two
failures: the inherited negotiated-drawer assertion and the API24 Honeyboard
case. All16 native API28/37 Honeyboard cases passed. The minimum-SDK case failed
in common setup/layout, before its test body or asynchronous IMM assertions,
because Robolectric4.17 legacy Paint returned zero font line spacing. It did
not establish an IMM-bridge failure or passing API24 input coverage.

The drawer fixture repair is integrated from actual correctness/keyboard main.
For API24 only, a method-scoped `ShadowPaint` supplies synthetic layout spacing
from its inherited configured text size. No existing setup/test body or queue
assertion is changed; `NoPty` and `RecordingImm` remain selected. Native API28/37
configuration is unchanged. The shim is **layout geometry, not font, Canvas or
pixel-fidelity evidence**; production font policy and renderer fields are not
changed. The [4.17 source](https://repo.maven.apache.org/maven2/org/robolectric/shadows-framework/4.17/shadows-framework-4.17-sources.jar)
provides protected text-size accessors but no spacing implementation, while
[API24 Paint](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-7.0.0_r1/graphics/java/android/graphics/Paint.java#L1687-L1689)
provides the actual public `getFontSpacing()` method. The test hook therefore
has `@Implementation` and deliberately no Java `@Override`.

This correction is independently source-reviewed. New-head CI must execute the
API24 body and fresh Pi Durable/Cursor reviews must finish before merge. The
asynchronous direct-target IMM bridge is still not full Android served-view or
ViewRoot routing, and no physical Honeyboard/Keys Cafe acceptance is claimed.
