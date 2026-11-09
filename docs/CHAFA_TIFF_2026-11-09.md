# Chafa iTerm TIFF: narrow fallback, source and limits

## Capability and provenance

Chafa can emit full-color RGBA images through iTerm OSC1337 rather than Sixel.
The selected adaptation derives from Matan Ziv-Av's open
[upstream PR5316](https://github.com/termux/termux-app/pull/5316), exact head
`6ec101ab65948bfc31c05775771f2bb4d9a92292`, base
`4584488513c099f2e98bcfcd00f006d213248729`. It is not a wholesale cherry-pick or a
claim that the upstream draft is approved.

The actual producer is Chafa 1.18.3, source pin
[`3b160556b3a6107196b614de1eb0634844144e15`](https://github.com/hpjansson/chafa/blob/3b160556b3a6107196b614de1eb0634844144e15/chafa/internal/chafa-iterm2-canvas.c#L197-L321),
verified Git blob `c5de18969b9adec0cb5ace25d4df0bcb9798a5c7`. This is source
provenance, not an assertion about an installed phone package. The fixture/proof
record is `app/src/test/resources/chafa-tiff-source-proof.json`.

## Supported subset and repaired conflicts

- Little-endian classic TIFF, one contiguous uncompressed RGBA8 strip at offset8,
  top-left orientation, trailing single IFD, four external 8-bit sample values,
  and unassociated alpha (`ExtraSamples=2`).
- The producer omits Compression, meaning default1. An equivalent tag ordering
  and explicit Compression1 are also accepted. This is deliberately a narrow
  layout, not arbitrary TIFF, multistrip, RGB24, palette, planar or 16-bit support.
- Complete tag/type/count/strip/IFD ranges and available pixel bytes are checked
  **before allocation**. Checked division precedes area multiplication.
- Decode directly at the requested sampling; convert with one output-width ARGB
  row and `Bitmap.setPixels`, which provides Android's premultiplied storage.
  The raw upstream copy ignored sampling/essential tags and alpha conversion.
- Reuse the existing sourced Android/Canvas geometry and byte checks. No new
  heap-fraction budget, global registry, full-image int array, explicit GC or
  recycling policy is introduced.
- **BitmapFactory is first for both bounds and pixels.** Android's native RAW/DNG
  path can accept TIFF containers; the fallback must not claim every `II/42`
  payload. Its native-first routing is source-reviewed, but no new actual native
  DNG fixture has been executed.

## User route and Pi distinction

With Chafa already available, its explicit route is:

```sh
chafa --format iterm ./preview.png
```

No package is installed/upgraded by this APK task, and this CLI has not been
executed as part of the current evidence. The test TIFF reconstructs the verified
production encoder layout; it is **not a captured Chafa process output**.

At the researched Pi pin `cd74c5a3615635c61e68c52922debcda7ed53b51`, ordinary
PNG/JPEG image support does not need TIFF. Pi can forward supplied TIFF bytes,
but its dimension helper does not recognize TIFF; custom callers must supply
correct dimensions rather than rely on its 800x600 fallback. Android decoding
cannot repair Pi's incorrectly reserved rows or fullscreen iTerm suppression.
The independently documented iTerm route is:

```sh
PI_IMAGE_PROTOCOL=iterm2 pi --tui-mode regular
```

See the full [launch/settings/restore/tmux limits](UPSTREAM_AUDIT_2026-11-09.md).

## Verification boundaries

Nine native-graphics test methods target SDK28/37 for opaque/translucent colors,
Canvas compositing, sampling, malformed/truncated/overflowing headers, unsupported
layouts, native PNG preference, single/multipart OSC and snapshot/GC/resize
lifetime. One SDK24 test is explicitly **LEGACY and parser-only**: Robolectric4.17
cannot provide native graphics at API24. These are authored regression cases;
actual exact-head CI results must be recorded separately.

Neither reconstructed fixtures nor PNG routing prove a real Chafa invocation,
native DNG regression coverage, ART/physical Fold rendering, heap-pressure
acceptance or tmux forwarding. Physical installation remains owner-controlled.
