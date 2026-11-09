#!/usr/bin/env node
// Execute the unchanged, externally supplied pinned Pi module. No copied/model emitter.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, realpath, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SOURCE_COMMIT = "cd74c5a3615635c61e68c52922debcda7ed53b51";
const SOURCE_BLOB = "c1f682dff617a3c0782a5f0dcf7d412d8d8c2418";
const SOURCE_SHA256 = "2aa0d1acfc233129e8ae15a6db607b8ea0873c665c0820e682233b118fbe09e1";
const INPUT_SHA256 = "7bf3077caa2df3daf223a76656c9ff0d6de71ec1b01fd157ac6b681f32d53b98";
const ITERM_SHA256 = "f0d3895a147106b072d7110a534486d737383141aa36991157d008613a51296e";
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
assert.equal(process.versions.node.split(".")[0], "24", "Use Node 24 native TypeScript support");
assert.equal(process.argv.length, 3, "Usage: node tools/ci/generate-pi-kitty-fixture.mjs /external/pinned/terminal-image.ts");
const sourcePath = await realpath(process.argv[2]);
const source = await readFile(sourcePath);
assert.equal(sha256(source), SOURCE_SHA256, "Pi source SHA-256 does not match the reviewed pin");
assert.equal(createHash("sha1").update(`blob ${source.length}\0`).update(source).digest("hex"), SOURCE_BLOB);
const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const resources = resolve(root, "app/src/test/resources");
const iterm = await readFile(resolve(resources, "pi-iterm2-preview.ansi"));
assert.equal(sha256(iterm), ITERM_SHA256, "Existing Pi iTerm fixture changed");
const match = /^\x1b\]1337;File=[^:]+:([A-Za-z0-9+/=]+)(?:\x07|\x1b\\)$/.exec(iterm.toString("ascii"));
assert.ok(match, "Expected exactly one production Pi iTerm PNG transmission");
const png = Buffer.from(match[1], "base64");
assert.equal(png.toString("base64"), match[1]);
assert.equal(sha256(png), INPUT_SHA256, "Recovered input PNG changed");
assert.equal(png.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
const dimensions = { widthPx: png.readUInt32BE(16), heightPx: png.readUInt32BE(20) };
assert.deepEqual(dimensions, { widthPx: 480, heightPx: 680 });

const pi = await import(pathToFileURL(sourcePath).href);
// Explicit public capability override, not a fabricated TERM/terminal identity.
pi.setCapabilities({ images: "kitty", trueColor: true, hyperlinks: false });
pi.setCellDimensions({ widthPx: 8, heightPx: 16 });
const imageId = 4294967294;
const rendered = pi.renderImage(png.toString("base64"), dimensions, {
  maxWidthCells: 12, maxHeightCells: 8, imageId, moveCursor: false,
});
assert.ok(rendered);
assert.equal(rendered.imageId, imageId);
assert.equal(rendered.rows, 8);
assert.equal(rendered.columns, 11);
const full = pi.getKittyImagePlacement(rendered.sequence);
const cropLine = pi.cropKittyImageLine(rendered.sequence, 2, 3);
const crop = pi.getKittyImagePlacement(cropLine);
const lowerCrop = pi.getKittyImagePlacement(pi.cropKittyImageLine(rendered.sequence, 5, 3));
assert.ok(full && crop && lowerCrop);
assert.equal(crop.rows, 3);
assert.equal(lowerCrop.rows, 3);
assert.ok(rendered.sequence.startsWith(`\x1b_Ga=T,f=100,q=2,C=1,c=11,r=8,i=${imageId},m=1;`));
assert.ok(rendered.sequence.includes("\x1b_Gm=0;"));
assert.ok(crop.sequence.includes("y=170,h=255,r=3"));
assert.ok(lowerCrop.sequence.includes("y=425,h=255,r=3"));
assert.ok(!full.sequence.includes(";"), "Production cache placement is payloadless");

const esc = "\x1b";
const position = (row, column) => `${esc}[${row};${column}H`;
const text = (row, column, value) => position(row, column) + value;
let stream = `${esc}]10;#d5deeb\x07${esc}]11;#101923\x07${esc}[2J${esc}[?25l`;
// Every graphics command below is returned by a real public Pi export.
stream += pi.deleteAllKittyImages();
stream += text(1, 2, `${esc}[1;36mPI / KITTY PREVIEW WORKBENCH${esc}[0m`);
stream += text(2, 2, "production emitter / pinned source");
stream += text(4, 2, `${esc}[32m$${esc}[0m PI_IMAGE_PROTOCOL=kitty pi`);
stream += text(5, 2, "q=2 C=1 / unsigned id 4294967294");
stream += text(6, 2, `${esc}[1;33m01 CACHE REUSE / original PNG${esc}[0m`);
stream += position(7, 3) + rendered.sequence;
stream += text(16, 2, `${esc}[1;33m02 CROP / cached source pixels${esc}[0m`);
stream += position(17, 3) + crop.sequence;
// Remove the original placements, keep the uploaded image, then reuse it three times.
stream += pi.deleteAllKittyPlacements();
stream += position(7, 3) + full.sequence;
stream += position(17, 3) + crop.sequence;
stream += text(22, 2, `${esc}[1;33m03 DELETE + REUSE / cache retained${esc}[0m`);
stream += position(23, 3) + lowerCrop.sequence;
stream += text(7, 19, "placement-only reuse");
stream += text(9, 19, "480 x 680 source PNG");
stream += text(11, 19, "i=4294967294");
stream += text(13, 19, "quiet / cursor held");
stream += text(17, 19, "y=170 h=255 r=3");
stream += text(19, 19, "no retransmission");
stream += text(23, 19, "delete d=a kept data");
stream += text(25, 19, "y=425 h=255 r=3");
stream += text(28, 2, "same fixture / before and after");
stream += text(30, 2, "native renderer / not a device capture");
stream += text(31, 2, "$ ready");
const output = Buffer.from(stream, "utf8");
const outputPath = resolve(resources, "pi-kitty-preview.ansi");
await writeFile(outputPath, output);
const proof = {
  sourceRepository: "fitchmultz/pi", sourceCommit: SOURCE_COMMIT,
  sourcePath: "packages/tui/src/terminal-image.ts", sourceGitBlob: SOURCE_BLOB,
  sourceSha256: SOURCE_SHA256,
  sourceUrl: `https://github.com/fitchmultz/pi/blob/${SOURCE_COMMIT}/packages/tui/src/terminal-image.ts`,
  node: process.version,
  generator: "tools/ci/generate-pi-kitty-fixture.mjs",
  generatorSha256: sha256(await readFile(fileURLToPath(import.meta.url))),
  scope: "Unchanged production Pi exports executed with Node 24; standard ANSI positions/text surround their actual graphics bytes. Native capture tests are separate and unrun at fixture generation; not a physical-device capture.",
  capabilitySelection: "Public setCapabilities({images: 'kitty', trueColor: true, hyperlinks: false}); no terminal identity spoof",
  input: { recoveredFrom: "app/src/test/resources/pi-iterm2-preview.ansi", containerSha256: ITERM_SHA256,
    description: "Existing synthetic production-Android renderer capture PNG, decoded without modification",
    sha256: INPUT_SHA256, bytes: png.length, ...dimensions },
  renderOptions: { maxWidthCells: 12, maxHeightCells: 8, imageId, moveCursor: false },
  exportsExecuted: ["setCapabilities", "setCellDimensions", "renderImage", "getKittyImagePlacement",
    "cropKittyImageLine", "deleteAllKittyImages", "deleteAllKittyPlacements"],
  commands: { transmittedPngs: 1, transmissionChunks: (rendered.sequence.match(/\x1b_G/g) || []).length,
    croppedPlacementSha256: sha256(Buffer.from(crop.sequence)),
    reusedPlacementSha256: sha256(Buffer.from(full.sequence)),
    lowerCropPlacementSha256: sha256(Buffer.from(lowerCrop.sequence)),
    lifecycle: "clear image data; transmit+place; crop/cache placement; delete placements retaining data; reuse full placement and two crops" },
  output: { path: "app/src/test/resources/pi-kitty-preview.ansi", protocol: "kitty", columns: rendered.columns,
    rows: rendered.rows, bytes: output.length, sha256: sha256(output) },
  nativeCapture: { test: "TerminalKittyImageRenderingTest", widths: [480, 900], height: 680,
    files: ["pi-kitty-cover.png", "pi-kitty-wide.png"],
    assertions: "Source hash, text, cursor and quiet replies only; no supported-image or pixel assertions",
    stateAtFixtureGeneration: "unrun; run unchanged test source on baseline and candidate" },
};
await writeFile(resolve(resources, "pi-kitty-emitter-proof.json"), JSON.stringify(proof, null, 2) + "\n");
console.log(JSON.stringify({ sourceSha256: SOURCE_SHA256, inputSha256: INPUT_SHA256, ...proof.output }, null, 2));
