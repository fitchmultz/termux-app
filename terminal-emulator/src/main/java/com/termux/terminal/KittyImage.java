package com.termux.terminal;

import java.util.Arrays;

/**
 * Direct-data Kitty graphics frontend, selectively adapted from upstream PR5238 at
 * 27eea1b963dce732e62d4f89ae0a9b71b86a0168 (KittyImage's streaming decoder).
 * https://sw.kovidgoyal.net/kitty/graphics-protocol/
 * Pixels and placements use TerminalBitmap/TerminalBuffer, not a second renderer.
 */
final class KittyImage {
    char action = 't', delete = 'a';
    int format = 32, quiet, columns, rows, pixelWidth, pixelHeight;
    int sourceX, sourceY, sourceWidth, sourceHeight;
    long imageId, placementId;
    boolean more, noMove, hasQuiet, continuation = true;
    String error;
    private byte[] data;
    private int length, bits, accumulator, padding, base64Length, expectedLength;

    KittyImage(StringBuilder header) {
        // Continue reading after an error so a later i/q still identifies/silences the reply.
        int start = 0;
        while (start < header.length()) {
            int end = header.indexOf(",", start);
            if (end < 0) end = header.length();
            if (end - start < 3 || header.charAt(start + 1) != '=') {
                fail("EINVAL");
            } else {
                char key = header.charAt(start);
                if (key != 'm' && key != 'q') continuation = false;
                String value = header.substring(start + 2, end);
                try {
                    switch (key) {
                        case 'a':
                            action = character(value);
                            if ("tTpqd".indexOf(action) < 0) fail("ENOTSUP");
                            break;
                        case 'd':
                            delete = character(value);
                            if ("aAiI".indexOf(delete) < 0) fail("ENOTSUP");
                            break;
                        case 't': if (character(value) != 'd') fail("ENOTSUP"); break;
                        case 'o': case 'e': fail("ENOTSUP"); break;
                        case 'f':
                            format = (int) number(value, 100);
                            if (format != 24 && format != 32 && format != 100) fail("ENOTSUP");
                            break;
                        case 'i': imageId = number(value, 0xffffffffL); break;
                        case 'p': placementId = number(value, 0xffffffffL); break;
                        case 'm': more = number(value, 1) == 1; break;
                        case 'q': quiet = (int) number(value, 2); hasQuiet = true; break;
                        case 'C': noMove = number(value, 1) == 1; break;
                        case 'c': columns = (int) number(value, TerminalBitmap.MAX_BITMAP_CELLS); break;
                        case 'r': rows = (int) number(value, TerminalBitmap.MAX_BITMAP_CELLS); break;
                        case 's': pixelWidth = (int) number(value, TerminalBitmap.MAX_BITMAP_DIMENSION); break;
                        case 'v': pixelHeight = (int) number(value, TerminalBitmap.MAX_BITMAP_DIMENSION); break;
                        case 'x': sourceX = (int) number(value, Integer.MAX_VALUE); break;
                        case 'y': sourceY = (int) number(value, Integer.MAX_VALUE); break;
                        case 'w': sourceWidth = (int) number(value, Integer.MAX_VALUE); break;
                        case 'h': sourceHeight = (int) number(value, Integer.MAX_VALUE); break;
                        // Do not silently turn virtual/relative/offset/stacked placements into
                        // ordinary cell images. Zero/default values require no extra capability.
                        case 'I': case 'U': case 'P': case 'Q': case 'X': case 'Y':
                        case 'H': case 'V': case 'z': case 'S': case 'O':
                            if (!value.equals("0")) fail("ENOTSUP");
                            break;
                        default: break; // Unknown extension keys are ignored by the protocol.
                    }
                } catch (IllegalArgumentException invalid) {
                    fail("EINVAL");
                }
            }
            start = end + 1;
        }
        if (more && action != 't' && action != 'T' && action != 'q') fail("EINVAL");
    }

    private static char character(String value) {
        if (value.length() != 1) throw new IllegalArgumentException();
        return value.charAt(0);
    }

    private static long number(String value, long maximum) {
        if (value.isEmpty() || value.length() > 10) throw new IllegalArgumentException();
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException();
            result = result * 10 + c - '0';
        }
        if (result > maximum) throw new IllegalArgumentException();
        return result;
    }

    void fail(String code) {
        if (error == null) error = code;
        data = null; // Failed/cancelled transfers never retain their large partial payload.
    }

    void continueWith(KittyImage next) {
        more = next.more;
        if (next.hasQuiet) quiet = next.quiet;
        if (next.error != null) fail(next.error);
    }

    void start() {
        if (error != null || action == 'p' || action == 'd' || data != null) return;
        if (format != 100) {
            if (!TerminalBitmap.isBitmapSizeValid(pixelWidth, pixelHeight)) {
                fail("EINVAL");
                return;
            }
            expectedLength = (int) ((long) pixelWidth * pixelHeight * (format / 8));
        }
    }

    /** Decode incrementally across APC/PTY boundaries; no Base64 text copy is retained. */
    void read(int c) {
        if (error != null) return;
        if (action == 'p' || action == 'd') { fail("EINVAL"); return; }
        if (c == '=') {
            if (more || ++padding > 2) { fail("EBADDATA"); return; }
            base64Length++;
            return;
        }
        int value = c >= 'A' && c <= 'Z' ? c - 'A'
            : c >= 'a' && c <= 'z' ? c - 'a' + 26
            : c >= '0' && c <= '9' ? c - '0' + 52 : c == '+' ? 62 : c == '/' ? 63 : -1;
        if (value < 0 || padding != 0) { fail("EBADDATA"); return; }
        base64Length++;
        accumulator = (accumulator << 6) | value;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (length >= TerminalBitmap.MAX_IMAGE_DATA_SIZE || (expectedLength > 0 && length >= expectedLength)) {
                fail("ENOSPC");
                return;
            }
            if (data == null || length == data.length) {
                int capacity = expectedLength > 0 ? expectedLength : (int) Math.min(
                    TerminalBitmap.MAX_IMAGE_DATA_SIZE, Math.max(4096L, length * 2L));
                try { data = data == null ? new byte[capacity] : Arrays.copyOf(data, capacity); }
                catch (OutOfMemoryError allocation) { fail("ENOSPC"); return; }
            }
            data[length++] = (byte) (accumulator >>> bits);
        }
    }

    byte[] finish() {
        if (error != null) return null;
        if (length == 0 || bits == 6 || (padding != 0 &&
            (base64Length % 4 != 0 || (padding == 1 ? bits != 2 : bits != 4)))) {
            fail("EBADDATA");
            return null;
        }
        if (format != 100 && length != expectedLength) { fail("EINVAL"); return null; }
        try { return data.length == length ? data : Arrays.copyOf(data, length); }
        catch (OutOfMemoryError allocation) { fail("ENOSPC"); return null; }
    }
}
