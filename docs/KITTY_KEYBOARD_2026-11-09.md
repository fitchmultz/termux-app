# Negotiated Kitty keyboard for Pi input

## Source and value

Adapts JM-FRANK <156544419@qq.com>, open upstream
[PR5366](https://github.com/termux/termux-app/pull/5366), exact head
`a9f40a5b813146091b41d0a130043bd0740beff8`, compared base
`8629e632fcb95da272221be327db653fb24befe9`. The four authored commits are
`60a581a175acbf632960af0a5226f90e94ca7818`,
`59c2f1ea591fed2c282380b0542597d4b5c29ba1`,
`817dfb4322a6e072bc006638da20e6e24532fc2b`, and the final head above.
Original production/input/parser test source is retained selectively; historical
validation dumps/tooling and unrelated changes are not imported. Upstream's
reported device/PTY execution counts are not this fork's execution evidence.

At public Pi research pin `cd74c5a3615635c61e68c52922debcda7ed53b51`, the terminal
requests flags7 (disambiguation1, event2, alternate-key4). Its default editor maps
Enter to submit and Shift+Enter/Ctrl+J to newline. This is genuine input value
without redefining every unnegotiated shell/TUI; PR5256's unconditional modified
Enter-to-LF remap remains excluded because it collapses identities and loses Alt
combinations. The pin is not proof of the installed phone revision.

## Preserved contracts and downstream repair

- Flags1|2|4 are negotiated. Without negotiation, retain legacy encoding.
- Main/alternate screens have independent bounded stacks and reset generations.
  Preserve PR5366's **32 entries including current mode**: 31 saved entries plus
  current1, with oldest eviction. This number is an upstream implementation
  policy, not mandated by the protocol: [pinned source](https://github.com/JM-FRANK/termux-app/blob/a9f40a5b813146091b41d0a130043bd0740beff8/terminal-emulator/src/main/java/com/termux/terminal/KittyKeyboardState.java#L5-L9)
  and [upstream documentation](https://github.com/JM-FRANK/termux-app/blob/a9f40a5b813146091b41d0a130043bd0740beff8/docs/kitty-keyboard.md#L8-L12).
- Preserve Fold shortcut, native-editor, pane and drawer precedence. Bind stored
  press/repeat/release identity to the actual session and protocol generation.
- Distinguish literal IME C0 bytes from explicit physical/toolbar modifiers. IME
  newline still becomes literal CR; do not misencode inferred Ctrl+M as Kitty
  `CSI 109;5u`. Explicit Ctrl and modified Enter remain negotiated.
- Keep existing bracketed multiline IME paste and normal composition semantics.
  Responsive-input admission is a later independent change; release bookkeeping
  must eventually remember only accepted presses, not rejected ones.
- No phone, shared Pi settings, Node/runtime, package, certificate, SDK or prefix
  change. Hardware layouts and virtual Fn+Ctrl combinations are not universally
  qualified by synthetic key events.

## Verification scope

Retains 37 upstream Android key-input methods and ten parser methods, adding a
DEC2026 interaction case, actual InputConnection flags1/7 controls/composition/
paste matrices, and Fold shortcut/pane-release/drawer coverage. These source
counts are not executed-test counts. Exact-head M1 Android CI and independent Pi
Durable/Cursor reviews are required before merge. Physical Samsung/IME/Bluetooth
acceptance remains unverified and owner-controlled, not an invented release gate.
