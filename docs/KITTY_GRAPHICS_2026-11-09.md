# Direct Kitty graphics frontend (8/8)

## Selected sources and scope

This is the previously selected eighth capability in
[the upstream selection](UPSTREAM_AUDIT_2026-11-09.md), not a wholesale graphics
stack import or an additional release gate. It stacks on PR35's checked common
Sixel/iTerm/Chafa bitmap backend (`3fd1d5d813f6855c69a7278cb1b32cbab340834e`).

- Upstream [PR5238 at `27eea1b9`](https://github.com/termux/termux-app/pull/5238/commits/27eea1b963dce732e62d4f89ae0a9b71b86a0168)
  provides the direct-data APC frontend and incremental Base64 decoder reference.
  Its pre-admission scaling, arbitrary cache quotas, unsupported-control ignoring,
  source-size assumptions and bitmap ownership are **not** imported.
- The [Kitty protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/) and
  [pinned Kitty source/docs](https://github.com/kovidgoyal/kitty/tree/3dc4f8d5aa0092b77c839145aff5602ca8664cd4)
  define unsigned32 identity, named replacement, cache placement, crop intersection,
  deletion and quiet replies. Actual `kitty/graphics.c` moves by effective rows−1,
  despite the prose saying rows. This frontend follows actual Kitty for that case.
  Both requested cell dimensions reserve a rectangle with aspect-preserving fit,
  following the protocol's current layout rule, not PR5238's unchecked stretch.
- Actual Pi's unchanged [`terminal-image.ts` at `cd74c5a3`](https://github.com/fitchmultz/pi/blob/cd74c5a3615635c61e68c52922debcda7ed53b51/packages/tui/src/terminal-image.ts)
  emits `a=T,f=100,q=2`, optional `C=1`, 4096-character chunks, unsigned random image
  IDs, payloadless cached `a=p`, source-pixel crops, and `d=a/A/I`. These are the
  production client requirements, not a custom emitter model.

Supported: direct `t=d`, `a=t/T/p/q/d`, PNG/RGB/RGBA `f=100/24/32`, unsigned image
and placement IDs, chunked uploads, `q=0/1/2`, cell sizing, source crop and `C=1`.
Named re-transmission removes old placements only after successful decoding;
named nonzero placement pairs replace previous placements. Anonymous/zero
placement IDs do not collapse independent placements. `d=a/A` selects visible
placements; named deletion can target a placement. Lowercase retains reusable
source data; uppercase frees it only without other live/history placements.
Sixel/iTerm images are not selected by Kitty deletion.

Filesystem/temp-file/shared-memory transports, zlib, animation, Unicode virtual
placements, relative placement/image-number identities, nondefault z-index and
subcell offsets are unsupported, not silently misrendered. Unknown extension
keys are ignored. No identity spoof, new permission, dependency, image package,
feature toggle, heap fraction/cache quota, allocator seam, or phone operation.

## Ownership and geometry

The existing Android/upstream draw-byte, Canvas-axis, encoded-data and packed-cell
bounds in `TerminalBitmap` remain authoritative. Raw geometry/byte counts and PNG
signature/decode bounds are checked before allocation. PNG dimensions come from
the actual decoded source, never the client's optional `s/v`. Crop coordinates
are applied to that original source before scaling. Requested and padded geometry
are checked with wide arithmetic before drawing; the raw PR's tiny-source
`c=2048,r=1024` scaling-before-admission bug is not imported. Allocation failures
are rejected without image/exception payload logging. No app-total/RSS promise.

Reusable immutable sources belong to each live screen buffer; placements use the
existing unsigned16 bitmap slots, image cells and renderer. Completed DEC2026
frames keep only their visible bitmap references and never copy the reusable
cache or in-progress upload. Deletion/replacement/collection never mutates or
recycles published pixels. Existing margin-aware scroll callbacks account for
transcript movement once. `C=1` clips the anchored placement at the bottom before
publication, without scrolling text; pinned Kitty `screen.c` scrolls only when its
graphics command changes cursor coordinates. Column/font-cell changes invalidate live fixed-geometry
placements but retain reusable source data for redraw; completed frames keep their
old pixels. Row-only resizing keeps the backend's ordinary row movement.

## Real Pi route and evidence boundary

For a fresh direct-Termux invocation, with Pi `terminal.images` unset/auto and
`terminal.showImages` enabled:

```sh
PI_IMAGE_PROTOCOL=kitty pi --tui-mode fullscreen
```

This uses Pi's existing per-process override, not a spoofed Kitty identity or
shared configuration change. Pi's ordinary Termux/unknown auto-detection still
falls back to no images. Explicit Pi image settings can override detection;
tmux forwarding is not established by an environment variable. No installed-phone
Pi revision, interactive full-session run or physical render is inferred.

Native tests use the existing API28/37 Robolectric Android pipeline and common
production parser/bitmap/renderer. The identical capture fixture is on baseline branch `fold/kitty-render-baseline-17`
at `4ecee689de7cb94f1e4dc090877d25e594240c05` and the candidate. Its 76,249 bytes
(SHA-256 `103e4ba835a596fa145d95291039be840cc3c21d9c8abd7845125efcf154954e`)
come from the pinned, unchanged production Pi module, actually executed with Node24.
`tools/ci/generate-pi-kitty-fixture.mjs` checks the source hash and regenerates the
resource deterministically; `pi-kitty-emitter-proof.json` records exact provenance.
The production fullscreen writer at that same Pi pin (`tui-alt-screen.ts` lines
1768–1786) explicitly draws images after text/clears for cell-backed renderers;
this frontend needs no separate overlay protocol or change to Pi. Captures
are synthetic test content rendered by production Android code, not Fold device
screenshots. Source fixture hashes and actual run/artifact links belong in the PR.
At preparation, authored new tests are **not yet run**. Full current-head Android
checks and genuine Cursor/Pi Durable review remain pending; no historical green
result is represented as new-head execution. Parent owns integration/release.
