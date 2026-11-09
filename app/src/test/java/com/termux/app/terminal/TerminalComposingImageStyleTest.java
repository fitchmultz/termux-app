package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRendererState;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Native renderer unit regressions using synthetic bitmap-cell metadata through the production
 * TextStyle/TerminalBuffer APIs. No image producer, bitmap registry, IME workflow or physical-device
 * rendering is simulated or claimed: these tests call renderComposingText directly.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalComposingImageStyleTest {
    private static final int TEXT_SIZE = 18;
    private static final int CURSOR_ROW = 1;
    private static final int CURSOR_COLUMN = 3;
    private static final int FOREGROUND = 0xffe6d9b8;
    private static final int BACKGROUND = 0xff172638;
    private static final int UNTOUCHED = 0xff9854ba;
    private static final String PREEDIT = "漢A";

    private static TerminalRenderer renderer() {
        return new TerminalRenderer(TEXT_SIZE, Typeface.MONOSPACE);
    }

    private static TerminalEmulator emulator() {
        TerminalRenderer metrics = renderer();
        TerminalEmulator terminal = new TerminalEmulator(new DiscardOutput(), 20, 5,
            (int) metrics.getFontWidth(), metrics.getFontLineSpacing(), 100,
            new TermuxTerminalSessionClientBase());
        append(terminal, "\033]10;#e6d9b8\007\033]11;#172638\007\033[2;4H");
        assertEquals(CURSOR_ROW, terminal.getCursorRow());
        assertEquals(CURSOR_COLUMN, terminal.getCursorCol());
        return terminal;
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private static void bitmapCell(TerminalEmulator terminal, int imageId, int x, int y) {
        // Deliberately metadata-only: exercise both payload namespaces without decoding 300 images.
        long style = TextStyle.encodeTerminalBitmap(imageId, x, y);
        terminal.getScreen().setChar(CURSOR_COLUMN, CURSOR_ROW, '+', style);
        assertTrue(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(CURSOR_ROW, CURSOR_COLUMN)));
        assertEquals(imageId, TextStyle.getTerminalBitmapNum(style));
    }

    private static Bitmap composing(TerminalRendererState state) {
        Bitmap bitmap = Bitmap.createBitmap(480, 200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(UNTOUCHED);
        char[] text = PREEDIT.toCharArray();
        // Fresh Paint state on each comparison; a previous bold run must not affect measurement.
        renderer().renderComposingText(state, canvas, 0, text, 0, text.length, 1);
        return bitmap;
    }

    private static int colorCount(Bitmap bitmap, int color) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int count = 0;
        for (int pixel : pixels) if (pixel == color) count++;
        return count;
    }

    private static void assertVisibleDefaultColors(Bitmap bitmap) {
        assertTrue("The composing glyph/caret must use the completed default foreground",
            colorCount(bitmap, FOREGROUND) > 0);
        assertTrue("The composing mask must use the completed default background",
            colorCount(bitmap, BACKGROUND) > 0);
        assertTrue("Pixels outside the composing run must remain untouched",
            colorCount(bitmap, UNTOUCHED) > 0);
    }

    private static void assertBitmapCellUsesNormalStyle(int imageId, int x, int y) {
        TerminalEmulator terminal = emulator();
        terminal.getScreen().setChar(CURSOR_COLUMN, CURSOR_ROW, ' ', TextStyle.NORMAL);
        Bitmap expected = composing(terminal.getRendererState());
        Bitmap actual = null;
        try {
            bitmapCell(terminal, imageId, x, y);
            actual = composing(terminal.getRendererState());
            assertTrue("Bitmap metadata must render like normal text style, not decoded image payload",
                expected.sameAs(actual));
            assertVisibleDefaultColors(actual);
        } finally {
            expected.recycle();
            if (actual != null) actual.recycle();
        }
    }

    @Test
    public void bitmapIdZeroDoesNotProduceBlackOnBlackPreedit() {
        assertBitmapCellUsesNormalStyle(0, 0, 0);
    }

    @Test
    public void bitmapId300IsNotUsedAsAnOutOfBoundsPaletteIndex() {
        assertBitmapCellUsesNormalStyle(300, 0, 0);
    }

    @Test
    public void bitmapCoordinatesAreNotUsedAsTextColorsEither() {
        assertBitmapCellUsesNormalStyle(0, 1, 768);
    }

    @Test
    public void bitmapFallbackKeepsCompletedCursorPaletteAndStyleDuringSynchronizedOutput() {
        TerminalEmulator terminal = emulator();
        terminal.getScreen().setChar(CURSOR_COLUMN, CURSOR_ROW, ' ', TextStyle.NORMAL);
        Bitmap expected = composing(terminal.getRendererState());
        Bitmap during = null;
        Bitmap after = null;
        try {
            bitmapCell(terminal, 300, 0, 0);
            append(terminal, "\033[?2026h");
            assertTrue(terminal.isSynchronizedOutputActive());
            TerminalRendererState completed = terminal.getRendererState();
            assertNotSame(terminal, completed);
            assertEquals(300, TextStyle.getTerminalBitmapNum(
                completed.getScreen().getStyleAt(CURSOR_ROW, CURSOR_COLUMN)));

            // The live cell is now colored bold/inverse text, with a new cursor and palette.
            // None of that parser-in-progress state may become the bitmap preview's fallback.
            append(terminal, "\033[2;4H\033[38;2;35;210;70;48;2;120;20;160;1;7mX"
                + "\033[4;11H\033]10;#ff0000\007\033]11;#00ff00\007\033[?5h");
            assertFalse(TextStyle.isTerminalBitmap(
                terminal.getScreen().getStyleAt(CURSOR_ROW, CURSOR_COLUMN)));
            assertEquals(CURSOR_ROW, completed.getCursorRow());
            assertEquals(CURSOR_COLUMN, completed.getCursorCol());
            assertEquals(FOREGROUND, completed.getPalette()[TextStyle.COLOR_INDEX_FOREGROUND]);
            assertEquals(BACKGROUND, completed.getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
            assertEquals(Color.RED, terminal.getPalette()[TextStyle.COLOR_INDEX_FOREGROUND]);
            assertEquals(Color.GREEN, terminal.getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
            assertSame(completed, terminal.getRendererState());

            during = composing(terminal.getRendererState());
            assertTrue("Pending image/text, SGR, cursor and palette changes must stay hidden",
                expected.sameAs(during));
            assertVisibleDefaultColors(during);

            append(terminal, "\033[?2026l");
            assertFalse(terminal.isSynchronizedOutputActive());
            after = composing(terminal.getRendererState());
            assertFalse("The newly completed cursor/palette must become visible", expected.sameAs(after));
        } finally {
            expected.recycle();
            if (during != null) during.recycle();
            if (after != null) after.recycle();
        }
    }

    @Test
    public void ordinaryColoredTextKeepsItsCellStyleInsteadOfNormalOrLivePenStyle() {
        TerminalEmulator terminal = emulator();
        int cellForeground = 0xff11bbcc;
        int cellBackground = 0xff551166;
        append(terminal, "\033[38;2;17;187;204;48;2;85;17;102;1;7m \033[2;4H\033[0m");
        assertFalse(TextStyle.isTerminalBitmap(
            terminal.getScreen().getStyleAt(CURSOR_ROW, CURSOR_COLUMN)));
        Bitmap before = composing(terminal.getRendererState());
        Bitmap after = null;
        try {
            assertTrue(colorCount(before, cellForeground) > 0);
            assertTrue(colorCount(before, cellBackground) > 0);
            assertEquals(0, colorCount(before, FOREGROUND));
            assertEquals(0, colorCount(before, BACKGROUND));
            // Change only the live SGR pen, not the already styled cell at the cursor.
            append(terminal, "\033[38;2;241;242;243;48;2;1;2;3;22;27m");
            after = composing(terminal.getRendererState());
            assertTrue("Non-bitmap cells must retain their original colors and effects", before.sameAs(after));
        } finally {
            before.recycle();
            if (after != null) after.recycle();
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
