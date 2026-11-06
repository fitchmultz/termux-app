# Native panes and Evidence Dock

## Native terminal splits

No tmux is required. Each pane owns its terminal view, PTY size, scroll position, selection, and text size.

- The compact shared header shows the session title. The **Sessions** menu at the bottom right opens the drawer, with **Compose** and **Split** actions near its bottom. Two visible panes retain their separate focus/maximize headers.
- **Split → Split with a new session** creates the second terminal.
- To use an existing session, long-press its drawer row and choose **Open beside current**.
- Tap a terminal or its header to select the destination for hardware input and extra keys. Keyboard session-switch shortcuts also move focus; delayed gestures from the previous pane are cancelled. When both panes are visible, the active pane has a teal border and a filled-circle header marker. A single visible terminal has neither.
- Drag the divider, or use **Split → Resize split** for an accessible slider. Each pane retains at least 25% of the split.
- **Split** also provides left/right, top/bottom, swap, and keep-active-only actions.
- The header's maximize button expands its pane; pressing it again restores the pair.
- On a wide-to-narrow window transition (below 600 dp), only the active pane is shown. Widening the window restores the pair. An explicit Restore split or Open beside action also permits splitting a narrow window.
- Hiding, maximizing, swapping, or removing a pane from the layout does **not** exit its session. The existing confirmed **Exit session** action does.
- Pairing, active pane, orientation, divider fraction, and pane font sizes survive activity recreation and leaving/reopening the activity while the service survives. This is not process resurrection after Android kills Termux.

## Adaptive extra keys

The default keys keep the same two rows when folding or rotating. They form a compact right-aligned group rather than stretching across the inner display. Width follows the rendered labels and at least 48dp touch targets; row height is at least 48dp. Custom matrices retain their configured rows and full-width layout.

| Ctrl / previous session | Alt / next session | Up / PageUp | Esc | Tab | Sessions |
| --- | --- | --- | --- | --- | --- |
| New session (+) | Left | Down / PageDown | Right | Next session | Keyboard / Compose |

A slash indicates the swipe-up action. The navigation arrows stay together; the frequent Esc, Tab, Sessions, Next, and Keyboard controls stay on the right. Sessions and Keyboard use consistent vector icons with accessibility labels. Resizing preserves button instances, latched modifiers, and popup actions.

Check narrow → wide → narrow transitions with the keyboard open and closed on the Fold 8 and Ultra. Verify session identity, unsent drafts, focus, and popup actions remain unchanged. Synthetic render/unit evidence does not replace physical typing and touch checks.

## Customize controls

Choose **Sessions → Customize controls**, or **Settings → Termux → Terminal I/O → Customize controls**. Hold and drag a key before another key, or onto **Add key** at a row's end. Tap a key for accessible move and remove actions. Add rows and choose keys, including Home, or create a literal-text key or space-separated macro.

**Save** applies the layout when you return to the terminal. **Cancel** discards edits. Existing macros, labels, and swipe-up actions stay attached to moved keys. Unsaved rows and custom-key text survive rotation. App layouts stay compact and scroll horizontally when a row exceeds the available width.

App layouts take precedence over `extra-keys` without editing `termux.properties`. **Reset** offers the right-handed Fold default or the file layout; either choice takes effect only after **Save**. Removing every key is allowed. Settings remains available through the session drawer's edge swipe.

## Evidence Dock

Choose **Sessions → Compose**, or swipe up on the Keyboard key, to open **Evidence Dock** above the extra keys. It starts collapsed so it does not consume terminal space until needed.

The displayed target is a **Termux session**, not a Pi agent identity or an individual tmux pane. Inside tmux, input still goes to that session's currently active tmux pane. There is no Posthorse dependency or agent coordination in the APK.

- Compose multiline text with Samsung's normal IME. Changing the active terminal switches to that session's own draft.
- **Add files** uses Android's document picker. Sharing text or files from another Android app to **Evidence Dock** asks which running session should receive the staged evidence. An unfinished share selection survives activity recreation; a completed or cancelled selection is not replayed.
- Imports copy granted `content://` streams into `~/.local/share/termux/evidence/`. Names are sanitized and unique. Files are streamed off the UI thread, with a 32 MiB limit per file and at most 8 files per draft. A failed batch does not add partial results to the draft.
- Attachments are inserted as local absolute path references. They are not binary terminal data or automatic uploads. The receiving application/agent must know how to read those paths.
- Tap a file chip to open it with an Android viewer or remove it from the draft. Removing a chip or clearing a draft does not delete imported files: already-delivered paths must remain usable. Manage old copies in the private evidence directory when no longer needed.
- The terminal selection context menu includes **Stage selection in Dock**.
- **Insert** pastes the draft without an extra Enter. **Send ↵** explicitly confirms pasting and pressing Enter in the named session.
- Both use the emulator's existing sanitization and bracketed-paste path. Multiline insertion is blocked if the receiving application has not enabled bracketed paste: otherwise an apparent paste can execute shell commands.
- Draft text is limited to 16,384 characters. Oversized external evidence is rejected rather than silently truncated.
- Drafts are stored in private app preferences at lifecycle/save boundaries, independent of Git and clipboard state. **More → Recover a saved draft** can copy an old session's draft into the current one after process death or an app update. Its confirmation also offers **Delete saved draft** for entries you no longer need.
- Shared evidence never starts a model turn or runs a command by itself. Only the explicit Insert/Send controls write to a PTY.

## Validation boundary

The package refresh leaves window sizing to Android in split-screen and Samsung Pop-up View, clearing stale fullscreen keyboard margins when entering either mode. Check entering and leaving those modes with the keyboard already open: the terminal must fill its window without flickering, and fullscreen typing must still keep the controls above the keyboard.

Automated checks exercise pane layout, focus/input routing, resize/maximize/recreation, draft isolation, bracketed insertion versus confirmed Send, rejected imports, and private copy limits. Hosted tests use synthetic terminal contents for render artifacts. Physical Fold checks remain necessary after installation: Samsung composition, cover/inner transitions, divider dragging, split-screen keyboard resizing, two live streams, and Android share/picker grants.
