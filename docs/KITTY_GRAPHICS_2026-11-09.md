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
`c=2048,r=1024` scaling-before-admission bug is not imported. A proper source
subrectangle is isolated only **after** these checks, then filtered at the existing
quality. Android's [source-rectangle bitmap draw](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/libs/hwui/SkiaCanvas.cpp#L588-L598)
uses Skia's fast constraint, which [allows filtering outside that rectangle](https://android.googlesource.com/platform/external/skia/+/android-9.0.0_r1/include/core/SkCanvas.h#1419).
The physical crop clamps filtering to selected pixels rather than contaminating
it with adjacent source pixels; it cannot exceed the already-validated decoded
source. This enforces crop-before-scale, not a pixel-identical Kitty GPU claim.
Allocation failures are rejected without image/exception payload logging. No
app-total/RSS promise.

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
## Native qualification checkpoint

- [Baseline Actions run42628156467](https://github.com/fitchmultz/termux-app/actions/runs/42628156467)
  at `4ecee689de7cb94f1e4dc090877d25e594240c05`: the identical native capture
  fixture passed both API28/37 cases and produced both PNGs in
  [reports artifact13680811883](https://github.com/fitchmultz/termux-app/actions/runs/42628156467/artifacts/13680811883).
  The overall run **failed** on the existing
  `KeyboardNavigationTest.composingConnectionRestartsAfterActualFinishedSessionRemoval`
  assertion; it is not a green baseline suite.
- [Initial candidate Actions run42629264130](https://github.com/fitchmultz/termux-app/actions/runs/42629264130)
  at `d86c54d9a303af164686ed3fcecaed03c6c46b78`: **failed**, with two failures
  among 424 app cases. Both were the API28/37 executions of
  `pngCropUsesSourcePixelsNotDeclaredSizeAndChecksScaleBeforeAllocation`.
  A crop selecting one green pixel produced `0xff1def0f` under filtered scaling.
  The original full-image bilinear weights at the assertion's source sample
  `(1.4375,0.5625)` predict that exact value; the Android/Skia source above explains
  the outside-crop sampling. The fix isolates the crop after admission, retaining
  exact-green assertions and additionally checking the entire 8x8 fit,
  transparent padding, PNG/RGB/RGBA and unmodified cached original reuse.
- That failed candidate run separately passed both actual pinned-Pi pixel
  acceptance cases and both native capture cases, with PNGs in
  [reports artifact13680758640](https://github.com/fitchmultz/termux-app/actions/runs/42629264130/artifacts/13680758640)
  (ZIP SHA-256 `62834b0959034232edc84bcb15f0086642490e3538be0516ffbe9a671d5888cb`).
  These establish actual producer/parser/renderer pixels, not a green overall
  run or a completed release-APK verification. The baseline keyboard failure did
  not recur in this candidate run; no cause or input fix is inferred.

- [Crop-fix Actions run42634570654](https://github.com/fitchmultz/termux-app/actions/runs/42634570654)
  at `eb15cd6010f800c4fe4d02c6ee45051aa5f790e9`: **all 24 Kitty graphics cases
  and both capture cases passed**, including strict PNG/RGB/RGBA crop/padding and
  original-cache reuse. The overall run **failed** the same existing keyboard
  assertion seen in the baseline (423/424 app cases passed); release-APK
  verification was skipped. [Reports artifact13683840549](https://github.com/fitchmultz/termux-app/actions/runs/42634570654/artifacts/13683840549)
  was downloaded and verified against SHA-256
  `c2344a64bd70552f12fd3536548ad24a75a618c4b6462f6db406937822d462de`.
  Both new capture PNGs are byte-identical to the initial candidate captures.
- Genuine [Pi review finding](https://github.com/fitchmultz/termux-app/pull/43#discussion_r4513963786):
  geometry-invalidating resize unconditionally called `clearApcTypeVariables()`,
  which also clears the buffer shared with non-APC OSC/DCS. Source tracing through
  `resize()`, `clearTerminalControlArgs()` and `doOsc()` confirms loss of the
  command prefix; a surviving tail `2` becomes OSC2 instead of the original OSC8.
  APC cleanup is now limited to the actual APC abort branch; the between-APC
  pending Kitty upload still clears independently. Pure-parser regressions retain
  title/color/OSC8 command identity and DCS query arguments across column/cell
  resize. Native SDK28/37 regression additionally requires the complete long
  OSC52 clipboard payload while cancelling between-command Kitty data, and checks
  both active-APC drain states. This does not change existing DCS-specific resize
  policy or add a production seam. Qualified-head execution is recorded below;
  no unpatched local Android reproduction is claimed.

Parent's real-preferences fixture correction at
`44324f1bb7b002ff6fbd93d281b1bc2115837681` is preserved. The qualified run below
verifies its stronger preconditions and original session assertions. No unrecorded
preference readback or causal explanation of the earlier failure is inferred.
## Qualified feature head

[Actions run42639240850](https://github.com/fitchmultz/termux-app/actions/runs/42639240850)
at `ef36c7e83a0f6949c67d504473243b92d7bb149e` passed all 637 tests across
56 XML reports, with no failures, errors or skips. The 26 native Kitty graphics
cases and both capture cases passed. This includes strict crop/padding assertions,
the actual pinned-Pi stream, full OSC52 clipboard preservation across resize,
active/pending Kitty upload cancellation, and the pure OSC/DCS parser regressions.
The finished-session keyboard case also passed with the stronger preference
preconditions. These results do not attribute the earlier failure to an
unrecorded preference state.

[Reports artifact13686165827](https://github.com/fitchmultz/termux-app/actions/runs/42639240850/artifacts/13686165827)
has ZIP SHA-256
`1124e03bc2a0b7d822e5f802cf6dffaa1fb051b5ae2f90f7d729796ba63c1880`.
The full Android test/APK check and both configured Cursor/Pi Durable reviews
passed on this feature head. These are PR-source results, not a physical-device
acceptance claim. The published release's `PROVENANCE` identifies its final
merged source and signing run.
