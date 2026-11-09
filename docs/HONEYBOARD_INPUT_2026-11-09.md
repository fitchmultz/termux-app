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

Five new source test methods cover Honeyboard versus other IMEs, actual arrows,
virtual context, stale connections and real-editor separation. The fixture matches
production focusability. Its native graphics cases use SDK28/37; API24 coverage is
explicitly legacy, not native graphics. Authoring/independent static reread and
patch/tree checks do not establish execution.

Exact-head M1 CI and independent Pi Durable/Cursor reviews are required. Current
Samsung One UI/Keys Cafe emission, hardware keyboards and physical Fold behavior
remain unverified until owner acceptance; they are not invented release-blocking
gates. The phone's ongoing source build must not be interrupted.
