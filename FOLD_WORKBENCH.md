# Native panes and Evidence Dock

## Native terminal splits

No tmux is required. Each pane owns its terminal view, PTY size, scroll position, selection, and text size.

- **Panes → Split with a new session** creates the second terminal.
- To use an existing session, long-press its drawer row and choose **Open beside current**.
- Tap a terminal or its header to select the destination for hardware input and extra keys. The active pane has a teal border and a filled-circle header marker.
- Drag the divider, or use **Panes → Resize split** for an accessible slider. Each pane retains at least 25% of the split.
- **Panes** also provides left/right, top/bottom, swap, and keep-active-only actions.
- The header's maximize button expands its pane; pressing it again restores the pair.
- On a wide-to-narrow window transition (below 600 dp), only the active pane is shown. Widening the window restores the pair. An explicit Restore split or Open beside action also permits splitting a narrow window.
- Hiding, maximizing, swapping, or removing a pane from the layout does **not** exit its session. The existing confirmed **Exit session** action does.
- Pairing, active pane, orientation, divider fraction, and pane font sizes survive activity recreation and leaving/reopening the activity while the service survives. This is not process resurrection after Android kills Termux.

## Evidence Dock

Open **Evidence Dock** above the terminals, or use the existing **TEXTBAR** extra-key popup. It starts collapsed so it does not consume terminal space until needed.

The displayed target is a **Termux session**, not a Pi agent identity or an individual tmux pane. Inside tmux, input still goes to that session's currently active tmux pane. There is no Posthorse dependency or agent coordination in the APK.

- Compose multiline text with Samsung's normal IME. Changing the active terminal switches to that session's own draft.
- **Add files** uses Android's document picker. Sharing text or files from another Android app to **Evidence Dock** asks which running session should receive the staged evidence.
- Imports copy granted `content://` streams into `~/.local/share/termux/evidence/`. Names are sanitized and unique. Files are streamed off the UI thread, with a 32 MiB limit per file and at most 8 files per draft. A failed batch does not add partial results to the draft.
- Attachments are inserted as local absolute path references. They are not binary terminal data or automatic uploads. The receiving application/agent must know how to read those paths.
- Tap a file chip to open it with an Android viewer or remove it from the draft. Removing a chip or clearing a draft does not delete imported files: already-delivered paths must remain usable. Manage old copies in the private evidence directory when no longer needed.
- The terminal selection context menu includes **Stage selection in Dock**.
- **Insert** pastes the draft without an extra Enter. **Send ↵** explicitly confirms pasting and pressing Enter in the named session.
- Both use the emulator's existing sanitization and bracketed-paste path. Multiline insertion is blocked if the receiving application has not enabled bracketed paste: otherwise an apparent paste can execute shell commands.
- Draft text is limited to 16,384 characters. Oversized external evidence is rejected rather than silently truncated.
- Drafts are stored in private app preferences at lifecycle/save boundaries, independent of Git and clipboard state. **More → Recover a saved draft** can copy an old session's draft into the current one after process death or an app update.
- Shared evidence never starts a model turn or runs a command by itself. Only the explicit Insert/Send controls write to a PTY.

## Validation boundary

Automated checks exercise pane layout, focus/input routing, resize/maximize/recreation, draft isolation, bracketed insertion versus confirmed Send, rejected imports, and private copy limits. Hosted tests use synthetic terminal contents for render artifacts. Physical Fold checks remain necessary after installation: Samsung composition, cover/inner transitions, divider dragging, split-screen keyboard resizing, two live streams, and Android share/picker grants.
