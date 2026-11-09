package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Real DCS parser, placement and native Bitmap coverage; no parser or graphics test seam. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalSixelDcsBoundaryTest {
    private static final String ESC = "\033";
    private static final String ST = ESC + "\\";
    private static final String RED = "#1;2;100;0;0#1!16~";
    private static final String DECRQSS = ESC + "P$q\"p" + ST;
    private static final String DECRQSS_REPLY = ESC + "P1$r64;1\"p" + ST;
    // The production control-args limit is 8192; data flushes at half that limit.
    // '?' advances the sixel cursor without painting, so '$q' after this prefix is visible.
    private static final String INTERNAL_FLUSH = "?".repeat(4096);

    private static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        @Override public void write(byte[] data, int offset, int count) { replies.write(data, offset, count); }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
        String text() { return replies.toString(StandardCharsets.UTF_8); }
    }

    private static TerminalEmulator terminal(Output output) {
        return new TerminalEmulator(output, 40, 12, 8, 16, 100, null);
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private static void appendChunks(TerminalEmulator terminal, String text, int chunkSize) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            byte[] chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkSize));
            terminal.append(chunk, chunk.length);
        }
    }

    private static int bitmapCells(TerminalEmulator terminal) {
        int cells = 0;
        for (int row = 0; row < terminal.getRows(); row++)
            for (int column = 0; column < terminal.getColumns(); column++)
                if (TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(row, column))) cells++;
        return cells;
    }

    private static Bitmap assertImageAndSafe(TerminalEmulator terminal, int cells) {
        TerminalBuffer screen = terminal.getScreen();
        assertEquals(cells, bitmapCells(terminal));
        long first = screen.getStyleAt(0, 0);
        assertTrue(TextStyle.isTerminalBitmap(first));
        Bitmap bitmap = screen.getSixelBitmap(first);
        assertNotNull(bitmap);
        assertEquals(cells * 8, bitmap.getWidth());
        assertEquals(16, bitmap.getHeight());
        for (int column = 0; column < cells; column++) {
            long style = screen.getStyleAt(0, column);
            assertTrue(TextStyle.isTerminalBitmap(style));
            assertEquals(column, TextStyle.getTerminalBitmapX(style));
            assertEquals(0, TextStyle.getTerminalBitmapY(style));
            assertSame(bitmap, screen.getSixelBitmap(style));
        }
        assertEquals("+".repeat(cells), screen.getSelectedText(0, 0, 39, 0));
        assertEquals("SAFE", screen.getSelectedText(0, 1, 39, 1));
        assertEquals("+".repeat(cells) + "\nSAFE", screen.getTranscriptText());
        assertEquals(1, terminal.getCursorRow());
        assertEquals(4, terminal.getCursorCol());
        return bitmap;
    }

    private static void assertQPixels(Bitmap bitmap) {
        // 'q' - '?' = 50: bits 1, 4 and 5 in the initial black color, not a request.
        for (int y = 0; y < 6; y++)
            assertEquals(y == 1 || y == 4 || y == 5 ? Color.BLACK : Color.TRANSPARENT,
                bitmap.getPixel(0, y));
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 6));
    }

    private static void assertQThenRed(Bitmap bitmap) {
        assertQPixels(bitmap);
        for (int x = 1; x <= 16; x++)
            for (int y = 0; y < 6; y++) assertEquals(Color.RED, bitmap.getPixel(x, y));
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(17, 0));
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(16, 6));
    }

    private static void assertPlainSafe(TerminalEmulator terminal) {
        assertEquals(0, bitmapCells(terminal));
        assertEquals("SAFE", terminal.getScreen().getTranscriptText());
        assertEquals(0, terminal.getCursorRow());
        assertEquals(4, terminal.getCursorCol());
    }

    @Test public void ordinarySixelStillPublishesTwoRedCells() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, ESC + "Pq" + RED + ST + "SAFE");
        Bitmap bitmap = assertImageAndSafe(terminal, 2);
        assertEquals(Color.RED, bitmap.getPixel(0, 0));
        assertEquals(Color.RED, bitmap.getPixel(15, 5));
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(15, 6));
        assertEquals("", output.text());
    }

    @Test public void initialDollarQPayloadIsSixelAtFirstCommandFlush() {
        for (String header : new String[]{"q", "0;0;0q", "2;0;0;q"}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            // The first '#' flushes the already recognized sixel payload '$q'.
            append(terminal, ESC + "P" + header + "$q" + RED + ST + "SAFE");
            assertQThenRed(assertImageAndSafe(terminal, 3));
            assertEquals("", output.text());
        }
    }

    @Test public void minimalDollarQPayloadPublishesAtStringTerminator() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, ESC + "Pq$q" + ST + "SAFE");
        Bitmap bitmap = assertImageAndSafe(terminal, 1);
        assertQPixels(bitmap);
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(1, 1));
        assertEquals("", output.text());
    }

    @Test public void dollarQAfterInternalDataFlushCannotBecomeDecrqss() {
        for (String suffix : new String[]{"$q", "$q" + RED}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            append(terminal, ESC + "Pq" + INTERNAL_FLUSH);
            assertEquals(0, bitmapCells(terminal));
            assertEquals("", terminal.getScreen().getTranscriptText());
            append(terminal, suffix + ST + "SAFE");
            // The transparent prefix sets source width to 4096; placement clips to 40 cells.
            Bitmap bitmap = assertImageAndSafe(terminal, 40);
            if (suffix.equals("$q")) {
                assertQPixels(bitmap);
                assertEquals(Color.TRANSPARENT, bitmap.getPixel(1, 1));
            } else {
                assertQThenRed(bitmap);
            }
            assertEquals("", output.text());
        }
    }

    @Test public void everySingleAppendSplitKeepsInitialDollarQInSixel() {
        String stream = ESC + "Pq$q" + RED + ST + "SAFE";
        for (int split = 0; split <= stream.length(); split++) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            append(terminal, stream.substring(0, split));
            append(terminal, stream.substring(split));
            assertQThenRed(assertImageAndSafe(terminal, 3));
            assertEquals("split=" + split, "", output.text());
        }
    }

    @Test public void byteAndChunkStreamingKeepPostFlushDollarQInSixel() {
        String stream = ESC + "Pq" + INTERNAL_FLUSH + "$q" + RED + ST + "SAFE";
        for (int chunkSize : new int[]{1, 2, 7, 4095, 4096, 4097}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            appendChunks(terminal, stream, chunkSize);
            assertQThenRed(assertImageAndSafe(terminal, 40));
            assertEquals("chunk=" + chunkSize, "", output.text());
        }
    }

    @Test public void validDecrqssHeaderStillRespondsAcrossAppendBoundaries() {
        for (int chunkSize : new int[]{1, 2, 3, 8}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            appendChunks(terminal, DECRQSS + "SAFE", chunkSize);
            assertEquals(DECRQSS_REPLY, output.text());
            assertPlainSafe(terminal);
        }
    }

    @Test public void unsupportedDecrqssRemainsSilentAndNextRequestResponds() {
        for (String request : new String[]{"$q", "$qm", "$q\"pX", "$q\\ignored"}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            appendChunks(terminal, ESC + "P" + request + ST, 1);
            assertEquals("", output.text());
            assertEquals("", terminal.getScreen().getTranscriptText());
            appendChunks(terminal, DECRQSS + "SAFE", 1);
            assertEquals(DECRQSS_REPLY, output.text());
            assertPlainSafe(terminal);
        }
    }

    @Test public void completedSixelReleasesHeaderStateForRealRequests() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        appendChunks(terminal, ESC + "Pq$q" + RED + ST + DECRQSS
            + ESC + "P+q436f;544e;5a5a" + ST + "SAFE", 1);
        assertQThenRed(assertImageAndSafe(terminal, 3));
        assertEquals(DECRQSS_REPLY + ESC + "P1+r436f=323536" + ST
            + ESC + "P1+r544e=787465726D" + ST + ESC + "P0+r5a5a" + ST, output.text());
    }

    @Test public void activeSixelCannotBecomeTermcapRequestEither() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        // '+' is invalid sixel: reject and drain, never reinterpret it as a fresh '+q' header.
        appendChunks(terminal, ESC + "Pq+q436f#1PRIVATE_REMAINDER", 1);
        assertEquals(0, bitmapCells(terminal));
        assertEquals("", output.text());
        assertEquals("", terminal.getScreen().getTranscriptText());
        appendChunks(terminal, ST + DECRQSS + "SAFE", 1);
        assertEquals(DECRQSS_REPLY, output.text());
        assertPlainSafe(terminal);
    }

    @Test public void canAndSubCancelInitialAndPostFlushSixelWithoutPublishing() {
        for (char cancel : new char[]{24, 26}) {
            for (String prefix : new String[]{"", INTERNAL_FLUSH}) {
                Output output = new Output();
                TerminalEmulator terminal = terminal(output);
                appendChunks(terminal, ESC + "Pq" + prefix + "$q" + RED, 7);
                assertEquals(0, bitmapCells(terminal));
                appendChunks(terminal, "" + cancel + DECRQSS + "SAFE", 1);
                assertEquals(DECRQSS_REPLY, output.text());
                assertPlainSafe(terminal);
            }
        }
    }

    @Test public void embeddedEscapeAndSplitStKeepExistingSixelRecovery() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        // Existing DCS behavior ignores a non-ST ESC, rather than printing or ending the payload.
        appendChunks(terminal, ESC + "Pq" + ESC + "$q" + RED + ESC, 1);
        assertEquals(0, bitmapCells(terminal));
        assertEquals("", output.text());
        appendChunks(terminal, "\\" + ESC + "[32mSAFE" + DECRQSS, 1);
        assertQThenRed(assertImageAndSafe(terminal, 3));
        assertEquals(DECRQSS_REPLY, output.text());
    }

    @Test public void rejectedLongPayloadDrainsThroughStAndDoesNotLeakRequestPrefixes() {
        for (String prefix : new String[]{"", INTERNAL_FLUSH}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            appendChunks(terminal, ESC + "Pq" + prefix + "$q!4294967296~#1"
                + "~".repeat(8192) + "$qPRIVATE" + ESC + "XSTILL_PRIVATE\\", 7);
            assertEquals(0, bitmapCells(terminal));
            assertEquals("", terminal.getScreen().getTranscriptText());
            assertEquals("", output.text());
            appendChunks(terminal, ST + DECRQSS + "SAFE", 1);
            assertEquals(DECRQSS_REPLY, output.text());
            assertPlainSafe(terminal);
        }
    }

    @Test public void canAndSubAlsoReleaseLongRejectedPayloadDrain() {
        for (char cancel : new char[]{24, 26}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            appendChunks(terminal, ESC + "Pq$q!4294967296~#1" + "~".repeat(8192) + ESC, 7);
            appendChunks(terminal, "" + cancel + DECRQSS + "SAFE", 1);
            assertEquals(DECRQSS_REPLY, output.text());
            assertPlainSafe(terminal);
        }
    }
}
