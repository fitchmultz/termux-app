package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.util.Base64;

import com.termux.terminal.TerminalBuffer;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Native bitmap decoding/rendering on API 28/37; API 24 covers parser-only behavior. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalInlineImagesTest {
    private static final String ST = "\033\\";
    private static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        @Override public void write(byte[] data, int offset, int count) { replies.write(data, offset, count); }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }

    private static TerminalEmulator terminal(Output output) {
        TerminalEmulator terminal = new TerminalEmulator(output, 40, 12, 8, 16, 100, null);
        append(terminal, "\033[?25l");
        return terminal;
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private static String image(int color) {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    private static String file(int color) {
        return "\033]1337;File=inline=1;width=2;height=1;preserveAspectRatio=0:" + image(color) + ST;
    }

    private static long firstImage(TerminalBuffer screen) {
        for (int row = 0; row < 12; row++) {
            for (int column = 0; column < 40; column++) {
                long style = screen.getStyleAt(row, column);
                if (TextStyle.isTerminalBitmap(style)) return style;
            }
        }
        fail("Expected a published inline image");
        return 0;
    }

    private static Bitmap render(TerminalEmulator terminal) {
        TerminalRenderer renderer = new TerminalRenderer(16, Typeface.MONOSPACE);
        Bitmap frame = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(terminal.getRendererState().getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
        renderer.render(terminal, canvas, 0, -1, -1, -1, -1);
        return frame;
    }

    /** Robolectric 4.17 uses legacy graphics below API 26: no bitmap allocation or pixel claims here. */
    @Test @Config(sdk = 24) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void oldestApiParsesCellSizeAndRejectedImageCommandsWithoutBitmapAllocation() {
        for (String terminator : new String[]{"\007", ST}) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            // Malformed headers and orphan parts return before image decoding/placement.
            // Do not call image(), file() or render() in this parser-only case.
            String commands = "\033]1337;ReportCellSize" + terminator
                + "\033]1337;FilePart=AAAA" + terminator + "\033]1337;FileEnd" + terminator
                + "\033]1337;File=inline=1;broken:AAAA" + terminator
                + "\033]1337;MultipartFile=inline=1;broken" + terminator
                + "\033]1337;FilePart=AAAA" + terminator + "\033]1337;FileEnd" + terminator
                + "SAFE";
            for (int i = 0; i < commands.length(); i++)
                append(terminal, commands.substring(i, i + 1));
            assertEquals("\033]1337;ReportCellSize=16;8\007", output.replies.toString(StandardCharsets.UTF_8));
            assertEquals("SAFE", terminal.getScreen().getTranscriptText());
            assertEquals(0, terminal.getCursorRow());
            assertEquals(4, terminal.getCursorCol());
            for (int row = 0; row < terminal.getRows(); row++)
                for (int column = 0; column < terminal.getColumns(); column++)
                    assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(row, column)));
        }
    }

    /** Fixture emitted by the unchanged production Pi module at the pin in pi-emitter-proof.json. */
    @Test public void actualPiItermEmissionReachesNativeParserAndRenderer() throws Exception {
        byte[] payload;
        try (InputStream input = getClass().getResourceAsStream("/pi-iterm2-preview.ansi")) {
            assertNotNull(input);
            payload = input.readAllBytes();
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder digest = new StringBuilder();
        for (byte value : hash) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals("f0d3895a147106b072d7110a534486d737383141aa36991157d008613a51296e", digest.toString());
        TerminalEmulator terminal = terminal(new Output());
        terminal.append(payload, payload.length);
        append(terminal, "PI_SOURCE_OK");
        Bitmap pixels = terminal.getScreen().getSixelBitmap(firstImage(terminal.getScreen()));
        assertEquals(64, pixels.getWidth());
        assertEquals(96, pixels.getHeight());
        assertEquals(0xff101923, pixels.getPixel(0, 0));
        assertEquals("PI_SOURCE_OK", terminal.getScreen().getSelectedText(8, 5, 19, 5));
        File image = new File("build/reports/tests/workbench/pi-iterm2-user-flow.png");
        assertTrue(image.getParentFile().isDirectory() || image.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(image)) {
            assertTrue(render(terminal).compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    @Test public void singleAndMultipartImagesDecodeAndReportCellSize() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, file(0xffff0000));
        long single = firstImage(terminal.getScreen());
        assertEquals(0xffff0000, terminal.getScreen().getSixelBitmap(single).getPixel(0, 0));
        append(terminal, "\033[4;1H\033]1337;MultipartFile=inline=1;width=2;height=1;preserveAspectRatio=0\007");
        String data = image(0xff00ff00);
        // Deliberately split Base64 at a non-quartet boundary and PTY input at every byte.
        String multipart = "\033]1337;FilePart=" + data.substring(0, 13) + "\007"
            + "\033]1337;FilePart=" + data.substring(13) + ST + "\033]1337;FileEnd\007";
        for (int i = 0; i < multipart.length(); i++) append(terminal, multipart.substring(i, i + 1));
        long second = terminal.getScreen().getStyleAt(3, 0);
        assertTrue(TextStyle.isTerminalBitmap(second));
        assertEquals(0xff00ff00, terminal.getScreen().getSixelBitmap(second).getPixel(0, 0));
        append(terminal, "\033]1337;ReportCellSize\007");
        assertEquals("\033]1337;ReportCellSize=16;8\007", output.replies.toString(StandardCharsets.UTF_8));
    }

    @Test public void imagesDoNotDropAdjacentTextRuns() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "AB" + file(0xffff0000) + "CD");
        assertEquals("AB++CD", terminal.getScreen().getSelectedText(0, 0, 5, 0));
        Bitmap actual = render(terminal);
        TerminalEmulator textOnly = terminal(new Output());
        append(textOnly, "AB  CD");
        Bitmap expectedText = render(textOnly);
        float cell = new TerminalRenderer(16, Typeface.MONOSPACE).getFontWidth();
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < (int) (2 * cell); x++)
                assertEquals("Text before image at " + x + "," + y, expectedText.getPixel(x, y), actual.getPixel(x, y));
            for (int x = (int) Math.ceil(4 * cell); x < (int) (6 * cell); x++)
                assertEquals("Text after image at " + x + "," + y, expectedText.getPixel(x, y), actual.getPixel(x, y));
        }
    }

    @Test public void synchronizedFramesRetainCompletedImagesAcrossEraseCollectionAndReplacement() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, file(0xffff0000));
        Bitmap completed = render(terminal);
        long original = firstImage(terminal.getScreen());
        Bitmap pixels = terminal.getScreen().getSixelBitmap(original);
        append(terminal, "\033[?2026h\033[2J\033[H");
        TerminalRendererState snapshot = terminal.getRendererState();
        terminal.getScreen().doTerminalBitmapsGC(0);
        assertNull(terminal.getScreen().getSixelBitmap(original));
        append(terminal, file(0xff0000ff));
        assertSame(pixels, snapshot.getScreen().getSixelBitmap(original));
        assertFalse(pixels.isRecycled());
        assertTrue(completed.sameAs(render(terminal)));
        append(terminal, "\033[?2026l");
        assertFalse(completed.sameAs(render(terminal)));
        assertEquals(0xff0000ff, terminal.getScreen().getSixelBitmap(firstImage(terminal.getScreen())).getPixel(0, 0));
    }

    @Test public void clearHistoryAndAttributeChangesPreserveVisibleImages() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, file(0xff00ff00));
        long style = firstImage(terminal.getScreen());
        Bitmap pixels = terminal.getScreen().getSixelBitmap(style);
        terminal.getScreen().clearTranscript();
        terminal.getScreen().setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_BOLD, true, false,
            true, 0, 40, 0, 0, 1, 2);
        assertSame(pixels, terminal.getScreen().getSixelBitmap(style));
        assertEquals(style, terminal.getScreen().getStyleAt(0, 0));
    }

    @Test public void sixelPublishesAndStreamsLongRasterDataWithoutLeakingIntoText() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "\033Pq#1;2;100;0;0#1!16~" + ST + "OK");
        long style = firstImage(terminal.getScreen());
        assertEquals(0xffff0000, terminal.getScreen().getSixelBitmap(style).getPixel(0, 0));
        assertTrue(terminal.getScreen().getTranscriptText().contains("OK"));
        TerminalEmulator wide = terminal(new Output());
        append(wide, "\033Pq#1;2;0;100;0#1" + "~".repeat(12000) + ST + "DONE");
        assertNotNull(wide.getScreen().getSixelBitmap(firstImage(wide.getScreen())));
        assertTrue(wide.getScreen().getTranscriptText().contains("DONE"));
        assertFalse(wide.getScreen().getTranscriptText().contains("~"));
    }

    @Test public void malformedImagesRecoverWithoutChangingPackageOrWritingFiles() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "\033]1337;FilePart=AAAA\007\033]1337;FileEnd\007");
        append(terminal, "\033]1337;File=inline=1;width=0;height=0:not-base64\007");
        append(terminal, "\033Pq\"1;1;4294967296;1#1~" + ST + "OK");
        assertTrue(terminal.getScreen().getTranscriptText().contains("OK"));
        assertTrue(terminal.getCursorCol() >= 0 && terminal.getCursorCol() < terminal.getColumns());
    }
    @Test public void imagePlacementReportsEveryScrollAndKeepsFinalColumnCursor() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "\033[12;1H\033Pq#1;2;100;0;0#1" + "!16~-".repeat(7) + "!16~" + ST);
        // Three image rows near the bottom require two placement scrolls plus the final LF.
        assertEquals(3, terminal.getScrollCounter());
        assertEquals(11, terminal.getCursorRow());
        assertEquals(3, terminal.getScreen().getActiveTranscriptRows());
        TerminalEmulator lastColumn = terminal(new Output());
        append(lastColumn, "\033[1;38H" + file(0xffff0000) + "Z");
        assertEquals("Z", lastColumn.getScreen().getSelectedText(39, 0, 39, 0));
    }

    @Test public void resizeDiscardsOnlyLiveImagePlacementsAndPreservesCompletedFrame() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "kept text\r\n" + file(0xffff0000));
        append(terminal, "\033[?2026h");
        TerminalBuffer snapshot = terminal.getRendererState().getScreen();
        long style = firstImage(snapshot);
        Bitmap pixels = snapshot.getSixelBitmap(style);
        terminal.resize(30, 12, 9, 18);
        assertSame(pixels, snapshot.getSixelBitmap(style));
        assertTrue(terminal.getScreen().getTranscriptText().contains("kept text"));
        for (int row = 0; row < 12; row++)
            for (int col = 0; col < 30; col++)
                assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(row, col)));
        append(terminal, "\033[?2026l");
        assertEquals(30, terminal.getRendererState().getColumns());
    }

    @Test public void rejectedSixelDrainsItsRemainingBytesThroughTerminator() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "\033Pq" + "~".repeat(4096) + "!4294967296~#1" + "~".repeat(8192) + ST + "SAFE");
        String text = terminal.getScreen().getTranscriptText();
        assertTrue(text.contains("SAFE"));
        assertFalse(text.contains("~"));
        assertFalse(text.contains("4294967296"));
    }

    @Test public void imageCompletionOutsideHorizontalMarginsDoesNotScrollProtectedRegion() {
        for (boolean sixel : new boolean[]{true, false}) {
            TerminalEmulator terminal = terminal(new Output());
            append(terminal, "\033[2;5HREGION\033[3;5HREGION\033[4;5HREGION"
                + "\033[?69h\033[5;20s\033[2;4r" + (sixel ? "\033[4;1H" : "\033[4;39H"));
            append(terminal, sixel ? "\033Pq#1;2;100;0;0#1!16~" + ST : file(0xffff0000));
            for (int row = 1; row < 4; row++)
                assertEquals("REGION", terminal.getScreen().getSelectedText(4, row, 9, row));
            assertEquals(0, terminal.getScrollCounter());
            assertEquals(3, terminal.getCursorRow());
        }
    }

    @Test public void malformedOscImageHeadersAndResizeDuringImageRecover() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "\033]1337\007\033]1337" + ST + "SAFE");
        assertEquals("SAFE", terminal.getScreen().getTranscriptText());
        append(terminal, "\033[2J\033[H\033[?7l\033Pq#1;2;100;0;0#1!4~");
        terminal.resize(30, 12, 9, 18);
        append(terminal, "PRIVATE_IMAGE_REMAINDER" + ST + "SAFE");
        assertEquals("SAFE", terminal.getScreen().getTranscriptText());
    }

    @Test public void graphicsRespectVerticalAndHorizontalScrollMargins() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "HEADER\033[5;1HFOOTER\033[2;4r\033[4;1H");
        String tall = "\033]1337;File=inline=1;width=2;height=2;preserveAspectRatio=0:" + image(0xffff0000) + ST;
        append(terminal, tall);
        assertEquals("HEADER", terminal.getScreen().getSelectedText(0, 0, 5, 0));
        assertEquals("FOOTER", terminal.getScreen().getSelectedText(0, 4, 5, 4));
        assertEquals(1, terminal.getScrollCounter());

        TerminalEmulator columns = terminal(new Output());
        append(columns, "LEFT\033[2;1HLEFT\033[3;1HLEFT\033[4;1HLEFT"
            + "\033[?69h\033[5;20s\033[2;4r\033[4;5H");
        append(columns, tall);
        for (int row = 0; row < 4; row++)
            assertEquals("LEFT", columns.getScreen().getSelectedText(0, row, 3, row));
        assertEquals(0, columns.getScrollCounter()); // LR copying moves no transcript rows (#5356).

        TerminalEmulator fullWidth = terminal(new Output());
        append(fullWidth, "\033[?69h\033[5;20s\033[2;4r\033[2;5H");
        append(fullWidth, "\033]1337;File=inline=1;width=16;height=1;preserveAspectRatio=0:" + image(0xff00ff00) + ST + "Z");
        assertTrue(TextStyle.isTerminalBitmap(fullWidth.getScreen().getStyleAt(1, 19)));
        assertFalse(TextStyle.isTerminalBitmap(fullWidth.getScreen().getStyleAt(1, 20)));
        assertEquals("Z", fullWidth.getScreen().getSelectedText(4, 2, 4, 2));
        assertEquals(2, fullWidth.getCursorRow());
        assertEquals(5, fullWidth.getCursorCol());
    }

}
