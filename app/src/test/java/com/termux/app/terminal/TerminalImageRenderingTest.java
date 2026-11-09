package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.util.Base64;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The same fixture runs on Fold16 and on the image-capable candidate. Unsupported image
 * commands are ignored by Fold16; no candidate-only APIs or expected-image assertions
 * belong here. Every screenshot pixel comes from the actual terminal renderer (including
 * the PNGs, which reach it only through the emulator's DCS/OSC parser).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalImageRenderingTest {
    private static final String ESC = "\033";
    private static final String ST = ESC + "\\";
    private static final int HEIGHT = 680;
    private static final int PADDING = 12;
    private static final int IMAGE_WIDTH = 144;
    private static final int IMAGE_HEIGHT = 72;

    /** Native raster captures, not a manually composed mock of the terminal UI. */
    @Test
    public void renderInlineImageWorkbench() throws Exception {
        capture(renderWorkbench(480), "inline-images-cover");
        capture(renderWorkbench(900), "inline-images-wide");
    }

    /** Package-visible so candidate-only tests may assert pixels without changing this fixture. */
    static Bitmap renderWorkbench(int width) {
        TerminalRenderer renderer = new TerminalRenderer(16, Typeface.MONOSPACE);
        int columns = (int) ((width - 2 * PADDING) / renderer.getFontWidth());
        int rows = (HEIGHT - 2 * PADDING) / renderer.getFontLineSpacing() - 1;
        TerminalEmulator terminal = new TerminalEmulator(new DiscardOutput(), columns, rows,
            (int) renderer.getFontWidth(), renderer.getFontLineSpacing(), 100,
            new TermuxTerminalSessionClientBase());
        assertTrue("Fixture needs room for all three protocol samples", rows >= 31);
        assertTrue("Fixture needs room for the text beside each image", columns >= 44);

        // The terminal owns the palette and all text layout, as it does in TerminalView.
        append(terminal, ESC + "]10;#d5deeb\007" + ESC + "]11;#101923\007"
            + ESC + "[2J" + ESC + "[?25l");
        text(terminal, 1, 2, ESC + "[1;36mTERMUX  /  IMAGE WORKBENCH" + ESC + "[0m");
        text(terminal, 2, 2, ESC + "[90m~/preview  main  |  native terminal canvas" + ESC + "[0m");
        text(terminal, 4, 2, ESC + "[32m$" + ESC + "[0m python render_preview.py");
        text(terminal, 5, 2, ESC + "[35mdef" + ESC + "[0m preview(): " + ESC + "[90m# inline raster output" + ESC + "[0m");
        text(terminal, 6, 2, "    " + ESC + "[36mreturn" + ESC + "[0m tiles, spectrum, heatmap");

        text(terminal, 8, 2, ESC + "[1;33m01  SIXEL" + ESC + "[0m  /  spectrum preview");
        // Same-row text before the bitmap catches a renderer that forgets to flush its run.
        // Text beside its final cell catches stale run start/index bookkeeping.
        text(terminal, 9, 2, "pre:");
        position(terminal, 9, 6);
        append(terminal, sixel());
        text(terminal, 9, 24, "after-sixel");
        text(terminal, 11, 24, ESC + "[90m144 x 72 pixels" + ESC + "[0m");
        text(terminal, 13, 2, ESC + "[32mPASS" + ESC + "[0m text after Sixel survives");

        byte[] singlePng = png(false);
        text(terminal, 15, 2, ESC + "[1;33m02  iTerm File" + ESC + "[0m  /  tile preview");
        text(terminal, 16, 2, "pre:");
        position(terminal, 16, 6);
        append(terminal, ESC + "]1337;File=" + imageArguments(singlePng.length)
            + ":" + Base64.encodeToString(singlePng, Base64.NO_WRAP) + "\007");
        text(terminal, 16, 24, "after-single");
        text(terminal, 18, 24, ESC + "[90mPNG / one OSC" + ESC + "[0m");
        text(terminal, 20, 2, ESC + "[32mPASS" + ESC + "[0m text after File survives");

        byte[] multipartPng = png(true);
        text(terminal, 22, 2, ESC + "[1;33m03  iTerm MultipartFile" + ESC + "[0m  /  heatmap");
        text(terminal, 23, 2, "pre:");
        position(terminal, 23, 6);
        append(terminal, ESC + "]1337;MultipartFile=" + imageArguments(multipartPng.length) + "\007");
        // Split the encoded stream, not the source bytes: padding belongs only at its end.
        String encodedMultipart = Base64.encodeToString(multipartPng, Base64.NO_WRAP);
        for (int offset = 0; offset < encodedMultipart.length(); offset += 200) {
            int end = Math.min(offset + 200, encodedMultipart.length());
            append(terminal, ESC + "]1337;FilePart=" + encodedMultipart.substring(offset, end) + "\007");
        }
        append(terminal, ESC + "]1337;FileEnd\007");
        text(terminal, 23, 24, "after-multipart");
        text(terminal, 25, 24, ESC + "[90mPNG / 200-char parts" + ESC + "[0m");
        text(terminal, 27, 2, ESC + "[32mPASS" + ESC + "[0m text after FileEnd survives");
        text(terminal, 29, 2, ESC + "[90m3 protocols  |  same parser + renderer" + ESC + "[0m");
        text(terminal, 31, 2, ESC + "[32m$" + ESC + "[0m ready for the next command");

        // Baseline invariants only: none depend on image support or image cell APIs.
        assertText(terminal, 9, 2, "pre:");
        assertText(terminal, 9, 24, "after-sixel");
        assertText(terminal, 16, 2, "pre:");
        assertText(terminal, 16, 24, "after-single");
        assertText(terminal, 23, 2, "pre:");
        assertText(terminal, 23, 24, "after-multipart");
        assertText(terminal, 13, 2, "PASS text after Sixel survives");
        assertText(terminal, 20, 2, "PASS text after File survives");
        assertText(terminal, 27, 2, "PASS text after FileEnd survives");
        assertText(terminal, 31, 2, "$ ready for the next command");

        Bitmap bitmap = Bitmap.createBitmap(width, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(terminal.getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
        canvas.save();
        canvas.translate(PADDING, PADDING);
        renderer.render(terminal, canvas, 0, -1, -1, -1, -1);
        canvas.restore();
        return bitmap;
    }

    private static String imageArguments(int bytes) {
        return "inline=1;size=" + bytes + ";width=" + IMAGE_WIDTH + "px;height=" + IMAGE_HEIGHT
            + "px;preserveAspectRatio=1";
    }

    /** A real, small sixel stream: twelve six-pixel-high bands of colored bars. */
    private static String sixel() {
        StringBuilder data = new StringBuilder(ESC + "Pq\"1;1;144;72"
            + "#1;2;20;80;95#2;2;45;90;55#3;2;100;75;25#4;2;90;35;60");
        for (int band = 0; band < IMAGE_HEIGHT / 6; band++) {
            for (int bar = 0; bar < 4; bar++) {
                data.append('#').append((bar + band / 3) % 4 + 1).append("!36~");
            }
            if (band + 1 < IMAGE_HEIGHT / 6) data.append('-');
        }
        return data.append(ST).toString();
    }

    /** Source pixels only, never drawn directly onto the screenshot canvas. */
    private static byte[] png(boolean heatmap) {
        int[] colors = heatmap
            ? new int[]{0xff253c78, 0xff277da8, 0xff43c8bb, 0xffbce35a, 0xfff5b85b, 0xffed6680}
            : new int[]{0xff4ccce5, 0xff6bdd95, 0xffffbf40, 0xffe65b99};
        int[] pixels = new int[IMAGE_WIDTH * IMAGE_HEIGHT];
        for (int y = 0; y < IMAGE_HEIGHT; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                int index = heatmap ? (x / 24 + y / 12) % colors.length : (x / 24 + y / 24) % colors.length;
                pixels[y * IMAGE_WIDTH + x] = colors[index];
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        bitmap.recycle();
        return encoded.toByteArray();
    }

    private static void position(TerminalEmulator terminal, int row, int column) {
        append(terminal, ESC + "[" + row + ";" + column + "H");
    }

    private static void text(TerminalEmulator terminal, int row, int column, String text) {
        position(terminal, row, column);
        append(terminal, text);
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // Deliberately split parser input, including image/control strings, like real PTY reads.
        for (int offset = 0; offset < bytes.length; offset += 97) {
            int count = Math.min(97, bytes.length - offset);
            byte[] part = new byte[count];
            System.arraycopy(bytes, offset, part, 0, count);
            terminal.append(part, count);
        }
    }

    private static void assertText(TerminalEmulator terminal, int row, int column, String expected) {
        assertEquals(expected, terminal.getScreen().getSelectedText(column - 1, row - 1,
            column + expected.length() - 2, row - 1));
    }

    /** Same report location and PNG compression as TerminalPaneLayoutTest.capture. */
    private static void capture(Bitmap bitmap, String name) throws Exception {
        try {
            File directory = new File("build/reports/tests/workbench");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static final class DiscardOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
}
