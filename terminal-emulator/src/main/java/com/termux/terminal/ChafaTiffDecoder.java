package com.termux.terminal;

import android.graphics.Bitmap;

/**
 * Fallback for Chafa's uncompressed iTerm image payload, not a general TIFF decoder.
 *
 * The wire layout is pinned to Chafa 1.18.3's production encoder:
 * https://github.com/hpjansson/chafa/blob/3b160556b3a6107196b614de1eb0634844144e15/chafa/internal/chafa-iterm2-canvas.c#L269-L321
 * It has a little-endian header, RGBA8 pixels at offset 8, one trailing IFD, and
 * four external BitsPerSample values. ExtraSamples=2 means UNASSOCIATED alpha.
 * The same tag set in another order, or with explicit Compression=1, is also accepted;
 * all field types/counts and pixel/directory placement must still match this layout.
 * BitmapFactory must be tried first: TIFF-container RAW/DNG is a native format.
 */
final class ChafaTiffDecoder {
    final int width;
    final int height;
    private final byte[] data;

    private ChafaTiffDecoder(byte[] data, int width, int height) {
        this.data = data;
        this.width = width;
        this.height = height;
    }

    /** Validate the complete layout and pixel span without allocating a bitmap or pixel array. */
    static ChafaTiffDecoder parse(byte[] data) {
        if (data == null || data.length < 8 || data[0] != 'I' || data[1] != 'I' ||
            data[2] != 42 || data[3] != 0) return null;

        long directory = u32(data, 4);
        if (directory < 8 || directory > data.length - 2L) return null;
        int entries = u16(data, (int) directory);
        // The encoder's eleven tags, optionally with explicit Compression=1 instead of its default.
        if (entries != 11 && entries != 12) return null;
        long directoryEnd = directory + 2 + 12L * entries + 4;
        if (directoryEnd + 8 != data.length || u32(data, (int) directoryEnd - 4) != 0) return null;

        long width = 0, height = 0, bits = 0, strip = 0, rows = 0, bytes = 0;
        int seen = 0;
        for (int i = 0; i < entries; i++) {
            int entry = (int) directory + 2 + 12 * i;
            int tag = u16(data, entry);
            int type = u16(data, entry + 2);
            long count = u32(data, entry + 4);
            long value = u32(data, entry + 8);
            boolean isLong = tag == 256 || tag == 257 || tag == 273 || tag == 278 || tag == 279;
            if (type != (isLong ? 4 : 3) || count != (tag == 258 ? 4 : 1)) return null;
            int flag;
            switch (tag) {
                case 256: flag = 1; width = value; break;
                case 257: flag = 1 << 1; height = value; break;
                case 258: flag = 1 << 2; bits = value; break;
                case 262: flag = 1 << 3; if (value != 2) return null; break; // RGB
                case 273: flag = 1 << 4; strip = value; break;
                case 274: flag = 1 << 5; if (value != 1) return null; break; // Top-left
                case 277: flag = 1 << 6; if (value != 4) return null; break; // RGBA
                case 278: flag = 1 << 7; rows = value; break;
                case 279: flag = 1 << 8; bytes = value; break;
                case 284: flag = 1 << 9; if (value != 1) return null; break; // Contiguous
                case 338: flag = 1 << 10; if (value != 2) return null; break; // Unassociated alpha
                case 259: flag = 1 << 11; if (value != 1) return null; break; // Uncompressed
                default: return null; // In particular, never claim a DNG or another TIFF layout.
            }
            if ((seen & flag) != 0) return null;
            seen |= flag;
        }
        if ((seen & 0x7ff) != 0x7ff || width == 0 || height == 0 ||
            width > Integer.MAX_VALUE || height > Integer.MAX_VALUE || strip != 8 ||
            rows != height || bits != directoryEnd) return null;

        // Check against available bytes before multiplying: unsigned TIFF dimensions can overflow long.
        if (width > (data.length - 8L) / 4 / height) return null;
        long pixelBytes = width * height * 4;
        if (bytes != pixelBytes || strip + pixelBytes != directory) return null;
        for (int i = 0; i < 4; i++) {
            if (u16(data, (int) bits + 2 * i) != 8) return null;
        }
        return new ChafaTiffDecoder(data, (int) width, (int) height);
    }

    /** Decode directly at the requested sample size, using only one output-width pixel row. */
    Bitmap decode(int sampleSize) {
        if (sampleSize < 1) throw new IllegalArgumentException("Invalid TIFF sample size");
        int outputWidth = (int) (((long) width + sampleSize - 1) / sampleSize);
        int outputHeight = (int) (((long) height + sampleSize - 1) / sampleSize);
        // Reuse the existing Android/Canvas limits; these are not an additional heap budget.
        Bitmap bitmap = TerminalBitmap.allocateBitmap(outputWidth, outputHeight);
        if (bitmap == null) return null;
        int[] row = new int[outputWidth];
        for (int y = 0; y < outputHeight; y++) {
            long sourceRow = 8L + (long) y * sampleSize * width * 4;
            for (int x = 0; x < outputWidth; x++) {
                int pixel = (int) (sourceRow + (long) x * sampleSize * 4);
                row[x] = ((data[pixel + 3] & 255) << 24) | ((data[pixel] & 255) << 16) |
                    ((data[pixel + 1] & 255) << 8) | (data[pixel + 2] & 255);
            }
            // setPixels accepts unassociated ARGB and converts to Android's premultiplied storage.
            // copyPixelsFromBuffer would incorrectly treat Chafa's unassociated RGBA as premultiplied.
            bitmap.setPixels(row, 0, outputWidth, 0, y, outputWidth, 1);
        }
        return bitmap;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8) |
            ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
}
