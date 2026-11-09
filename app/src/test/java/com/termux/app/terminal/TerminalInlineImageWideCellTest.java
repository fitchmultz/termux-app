package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.Base64;

import com.termux.terminal.TerminalBitmap;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Real parser -> shared bitmap builder -> row/renderer coverage, with no bitmap shadows. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalInlineImageWideCellTest {
    private static final int COLUMNS = 12, ROWS = 6, IMAGE_ROW = 2;
    private static final int CELL_WIDTH = 8, CELL_HEIGHT = 16;
    private static final int RED = 0xffff0000, GREEN = 0xff00ff00;
    private static final String ST = "\033\\";
    private static final String TEXT = "abcdefghijkl";
    private static final String OLD_TEXT_SGR = "\033[1;4;38;2;18;52;86;48;2;101;67;33m";
    private enum Protocol { FILE, MULTIPART, SIXEL }

    private static TerminalEmulator terminal() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
        TerminalEmulator terminal = new TerminalEmulator(output, COLUMNS, ROWS,
            CELL_WIDTH, CELL_HEIGHT, 100, null);
        append(terminal, "\033[?25l\033[1;1HHEADER\033[6;1HFOOTER" + cup(IMAGE_ROW, 0) + TEXT);
        return terminal;
    }

    private static String cup(int row, int column) {
        return "\033[" + (row + 1) + ";" + (column + 1) + "H";
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private static String imageData(int cells) {
        Bitmap bitmap = Bitmap.createBitmap(cells * CELL_WIDTH, CELL_HEIGHT, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++)
                bitmap.setPixel(x, y, (x / CELL_WIDTH) % 2 == 0 ? RED : GREEN);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    private static void image(TerminalEmulator terminal, Protocol protocol, int cells) {
        String command;
        if (protocol == Protocol.SIXEL) {
            // Fresh valid header/raster: deliberately independent of the separate '$q' DCS bug.
            StringBuilder sixel = new StringBuilder("\033Pq\"1;1;")
                .append(cells * CELL_WIDTH).append(';').append(CELL_HEIGHT)
                .append("#1;2;100;0;0#2;2;0;100;0");
            for (int cell = 0; cell < cells; cell++)
                sixel.append('#').append(cell % 2 + 1).append('!').append(CELL_WIDTH).append('~');
            command = sixel.append(ST).toString();
        } else {
            String args = "inline=1;width=" + cells + ";height=1;preserveAspectRatio=0";
            String data = imageData(cells);
            if (protocol == Protocol.FILE) {
                command = "\033]1337;File=" + args + ':' + data + ST;
            } else {
                // Non-quartet multipart boundary, and byte-at-a-time PTY input for every protocol.
                command = "\033]1337;MultipartFile=" + args + "\007"
                    + "\033]1337;FilePart=" + data.substring(0, 13) + ST
                    + "\033]1337;FilePart=" + data.substring(13) + "\007\033]1337;FileEnd" + ST;
            }
        }
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) terminal.append(new byte[]{value}, 1);
    }

    private static void wide(TerminalEmulator terminal, int firstColumn) {
        append(terminal, cup(IMAGE_ROW, firstColumn) + OLD_TEXT_SGR + "漢\u0308\033[0m");
    }

    private static void margins(TerminalEmulator terminal) {
        append(terminal, "\033[?69h\033[5;8s\033[2;5r"); // [4,8) x [1,5), zero-based.
    }

    private static long[][] styles(TerminalEmulator terminal) {
        long[][] styles = new long[ROWS][COLUMNS];
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLUMNS; col++)
                styles[row][col] = terminal.getScreen().getStyleAt(row, col);
        return styles;
    }

    private static void assertPlacement(TerminalEmulator terminal, Protocol protocol,
                                        int column, int cells, int right, long[][] oldTextStyles, String expectedRow) {
        TerminalBuffer screen = terminal.getScreen();
        long first = screen.getStyleAt(IMAGE_ROW, column);
        assertTrue(TextStyle.isTerminalBitmap(first));
        int id = TextStyle.getTerminalBitmapNum(first);
        TerminalBitmap image = screen.getTerminalBitmap(first);
        assertNotNull(image);
        assertEquals(CELL_WIDTH, image.getCellWidth());
        assertEquals(CELL_HEIGHT, image.getCellHeight());
        assertEquals(1, image.getScrollLines());
        assertEquals(0, image.getBufferScrollLines());
        if (protocol == Protocol.SIXEL) assertNull(image.getCursorDelta());
        // Emulator completion adjusts the very same stored array when the image does not wrap.
        else assertArrayEquals(new int[]{column + cells < right ? 0 : 1, cells}, image.getCursorDelta());
        Bitmap pixels = image.getBitmap();
        assertEquals(cells * CELL_WIDTH, pixels.getWidth());
        assertEquals(CELL_HEIGHT, pixels.getHeight());
        for (int cell = 0; cell < cells; cell++) {
            assertEquals(cell % 2 == 0 ? RED : GREEN, pixels.getPixel(cell * CELL_WIDTH + 4, 2));
            assertEquals(protocol == Protocol.SIXEL ? 0 : cell % 2 == 0 ? RED : GREEN,
                pixels.getPixel(cell * CELL_WIDTH + 4, CELL_HEIGHT - 1));
        }
        for (int row = 0; row < ROWS; row++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            assertEquals(row == IMAGE_ROW, line.mHasTerminalBitmap);
            assertFalse(screen.getLineWrap(row));
            assertEquals(COLUMNS, line.getSpaceUsed());
            for (int col = 0; col <= COLUMNS; col++) assertEquals(col, line.findStartOfColumn(col));
            for (int col = 0; col < COLUMNS; col++) {
                long style = screen.getStyleAt(row, col);
                if (row == IMAGE_ROW && col >= column && col < column + cells) {
                    int x = col - column;
                    assertEquals(TextStyle.encodeTerminalBitmap(id, x, 0), style);
                    assertSame(image, screen.getTerminalBitmap(style));
                    assertEquals(new Rect(x * CELL_WIDTH, 0, (x + 1) * CELL_WIDTH, CELL_HEIGHT),
                        screen.getSixelRect(style));
                } else {
                    assertFalse("Unexpected bitmap at " + row + "," + col, TextStyle.isTerminalBitmap(style));
                    assertEquals("Changed text style at " + row + "," + col, oldTextStyles[row][col], style);
                }
            }
        }
        TerminalRow row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(IMAGE_ROW));
        assertEquals(expectedRow, new String(row.mText, 0, row.getSpaceUsed()));
        assertEquals("HEADER", screen.getSelectedText(0, 0, 5, 0));
        assertEquals("FOOTER", screen.getSelectedText(0, ROWS - 1, 5, ROWS - 1));
        assertEquals(0, terminal.getScrollCounter());
        assertEquals(0, screen.getActiveTranscriptRows());
    }

    private static String expectedRow(int wideFirst, int imageColumn, int cells) {
        char[] row = TEXT.toCharArray();
        row[wideFirst] = row[wideFirst + 1] = ' ';
        for (int col = imageColumn; col < imageColumn + cells; col++) row[col] = '+';
        return new String(row);
    }

    private static void assertCursorAndMargins(TerminalEmulator terminal, Protocol protocol,
                                               int column, int cells, int left, int right, int top, int bottom) {
        boolean wrappedFile = protocol != Protocol.SIXEL && column + cells == right;
        assertEquals(IMAGE_ROW + (protocol == Protocol.SIXEL || wrappedFile ? 1 : 0), terminal.getCursorRow());
        assertEquals(protocol == Protocol.SIXEL ? column : wrappedFile ? left : column + cells, terminal.getCursorCol());
        // Verify margins using their real origin-mode cursor bounds, without reflection/test seams.
        append(terminal, "\033[?6h\033[1;1H");
        assertEquals(top, terminal.getCursorRow());
        assertEquals(left, terminal.getCursorCol());
        append(terminal, "\033[999;999H");
        assertEquals(bottom - 1, terminal.getCursorRow());
        assertEquals(right - 1, terminal.getCursorCol());
        append(terminal, "\033[?6l");
    }

    private static Bitmap render(TerminalEmulator terminal, TerminalRenderer renderer) {
        Bitmap frame = Bitmap.createBitmap(200, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(terminal.getRendererState().getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
        renderer.render(terminal, canvas, 0, -1, -1, -1, -1);
        return frame;
    }

    private static void assertRenderingOutsideImage(TerminalEmulator actual, TerminalEmulator reference,
                                                    int column, int cells) {
        TerminalRenderer renderer = new TerminalRenderer(16, Typeface.MONOSPACE);
        Bitmap actualFrame = render(actual, renderer), expected = render(reference, renderer);
        int left = (int) Math.floor(column * renderer.getFontWidth());
        int right = (int) Math.ceil((column + cells) * renderer.getFontWidth());
        for (int y = 0; y < actualFrame.getHeight(); y++)
            for (int x = 0; x < actualFrame.getWidth(); x++)
                if (x < left || x >= right)
                    assertEquals("Outside image pixel " + x + "," + y, expected.getPixel(x, y), actualFrame.getPixel(x, y));
    }

    @Test public void oneAndMultipleCellsAtSecondHalfUseOnlyTheirActualSlices() {
        for (Protocol protocol : Protocol.values()) {
            for (int cells : new int[]{1, 2}) {
                TerminalEmulator terminal = terminal();
                wide(terminal, 2);
                long[][] old = styles(terminal);
                append(terminal, cup(IMAGE_ROW, 3));
                image(terminal, protocol, cells);
                assertPlacement(terminal, protocol, 3, cells, COLUMNS, old, expectedRow(2, 3, cells));
                assertCursorAndMargins(terminal, protocol, 3, cells, 0, COLUMNS, 0, ROWS);
            }
        }
    }

    @Test public void leftMarginSecondHalfKeepsOutsideTextStyleAndNeverDrawsAnExtraSlice() {
        for (Protocol protocol : Protocol.values()) {
            for (int cells : new int[]{1, 2}) {
                TerminalEmulator terminal = terminal();
                wide(terminal, 3); // Wide glyph straddles the subsequently installed left margin.
                margins(terminal);
                long[][] old = styles(terminal);
                append(terminal, cup(IMAGE_ROW, 4));
                image(terminal, protocol, cells);
                assertPlacement(terminal, protocol, 4, cells, 8, old, expectedRow(3, 4, cells));
                TerminalEmulator reference = terminal();
                wide(reference, 3);
                append(reference, cup(IMAGE_ROW, 3) + OLD_TEXT_SGR + " \033[0m"
                    + cup(IMAGE_ROW, 4) + " ".repeat(cells));
                assertRenderingOutsideImage(terminal, reference, 4, cells);
                assertCursorAndMargins(terminal, protocol, 4, cells, 4, 8, 1, 5);
            }
        }
    }

    @Test public void rightMarginClipsOneAndMultipleCellImagesAtSecondHalf() {
        for (Protocol protocol : Protocol.values()) {
            for (int requested : new int[]{1, 3}) {
                TerminalEmulator terminal = terminal();
                wide(terminal, 6);
                margins(terminal);
                long[][] old = styles(terminal);
                append(terminal, cup(IMAGE_ROW, 7));
                image(terminal, protocol, requested);
                assertPlacement(terminal, protocol, 7, 1, 8, old, expectedRow(6, 7, 1));
                assertCursorAndMargins(terminal, protocol, 7, 1, 4, 8, 1, 5);
            }
        }
    }

    @Test public void lastScreenColumnSecondHalfHasNoDuplicateAndKeepsProtocolCursorRules() {
        for (Protocol protocol : Protocol.values()) {
            TerminalEmulator terminal = terminal();
            wide(terminal, COLUMNS - 2);
            long[][] old = styles(terminal);
            append(terminal, cup(IMAGE_ROW, COLUMNS - 1));
            image(terminal, protocol, 3);
            assertPlacement(terminal, protocol, COLUMNS - 1, 1, COLUMNS, old, expectedRow(COLUMNS - 2, COLUMNS - 1, 1));
            assertCursorAndMargins(terminal, protocol, COLUMNS - 1, 1, 0, COLUMNS, 0, ROWS);
        }
    }

    @Test public void firstHalfAtRightMarginClearsStaleImageStyleFromGeneratedOutsideBlank() {
        for (Protocol protocol : Protocol.values()) {
            TerminalEmulator terminal = terminal();
            append(terminal, cup(IMAGE_ROW, 8));
            image(terminal, Protocol.FILE, 1); // Real old bitmap style, later covered by a wide text glyph.
            // No combining mark here: the ordinary combining path styles the wide glyph's second half.
            append(terminal, cup(IMAGE_ROW, 7) + OLD_TEXT_SGR + "漢\033[0m");
            margins(terminal);
            long[][] old = styles(terminal);
            assertTrue(TextStyle.isTerminalBitmap(old[IMAGE_ROW][8]));
            old[IMAGE_ROW][8] = TextStyle.NORMAL;
            append(terminal, cup(IMAGE_ROW, 7));
            image(terminal, protocol, 1);
            assertPlacement(terminal, protocol, 7, 1, 8, old, expectedRow(7, 7, 1));
            assertCursorAndMargins(terminal, protocol, 7, 1, 4, 8, 1, 5);
        }
    }
}
