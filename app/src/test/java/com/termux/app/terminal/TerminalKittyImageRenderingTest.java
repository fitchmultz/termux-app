package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Run this exact source unchanged on the pre-Kitty baseline and the candidate. The fixture's
 * graphics bytes come from unchanged pinned production Pi exports, not a model emitter. All
 * assertions concern ordinary text, framing, quiet replies or cursor positions, never image
 * support/pixels. Screenshots use production Android decoding, emulator and TerminalRenderer;
 * they are synthetic native renders, not physical-device captures.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalKittyImageRenderingTest {
    private static final String ESC = "\033";
    private static final String ST = ESC + "\\";
    private static final int HEIGHT = 680;
    private static final int PADDING = 12;
    private static final String FIXTURE_SHA256 =
        "103e4ba835a596fa145d95291039be840cc3c21d9c8abd7845125efcf154954e";

    @Test
    public void capturePinnedPiKittyPreviewWorkbench() throws Exception {
        capture(renderWorkbench(480), "pi-kitty-cover");
        capture(renderWorkbench(900), "pi-kitty-wide");
    }

    /** Package-visible for candidate-only pixel tests without changing this baseline fixture. */
    static Bitmap renderWorkbench(int width) throws Exception {
        TerminalRenderer renderer = new TerminalRenderer(16, Typeface.MONOSPACE);
        int columns = (int) ((width - 2 * PADDING) / renderer.getFontWidth());
        int rows = (HEIGHT - 2 * PADDING) / renderer.getFontLineSpacing() - 1;
        Output output = new Output();
        TerminalEmulator terminal = new TerminalEmulator(output, columns, rows,
            (int) renderer.getFontWidth(), renderer.getFontLineSpacing(), 100,
            new TermuxTerminalSessionClientBase());
        assertTrue("Fixture needs room for text beside the previews", columns >= 44);
        assertTrue("Fixture needs room for all three previews", rows >= 31);

        byte[] fixture;
        try (InputStream input = TerminalKittyImageRenderingTest.class
                .getResourceAsStream("/pi-kitty-preview.ansi")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(fixture))
            digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        assertEquals(FIXTURE_SHA256, digest.toString());

        // Standard positions/text are fixture scaffolding. Every graphics APC is an actual Pi
        // export. Checking cursor stability after each APC works both when ignored by the
        // baseline and when admitted with q=2/C=1 by a supporting implementation.
        String stream = new String(fixture, StandardCharsets.UTF_8);
        int offset = 0;
        int commands = 0;
        while (offset < stream.length()) {
            int start = stream.indexOf(ESC + "_G", offset);
            if (start < 0) {
                append(terminal, stream.substring(offset));
                break;
            }
            append(terminal, stream.substring(offset, start));
            int end = stream.indexOf(ST, start + 3);
            assertTrue("Every captured APC has its native ST terminator", end >= 0);
            int row = terminal.getCursorRow();
            int column = terminal.getCursorCol();
            append(terminal, stream.substring(start, end + ST.length()));
            assertEquals("No graphics command should move this fixture's cursor", row, terminal.getCursorRow());
            assertEquals("No graphics command should move this fixture's cursor", column, terminal.getCursorCol());
            offset = end + ST.length();
            commands++;
        }
        assertEquals("Pinned stream contains one 19-chunk upload and quiet cache operations", 25, commands);
        assertEquals("q=2 must remain quiet across continuation chunks", 0, output.replies.size());
        assertText(terminal, 1, 2, "PI / KITTY PREVIEW WORKBENCH");
        assertText(terminal, 5, 2, "q=2 C=1 / unsigned id 4294967294");
        assertText(terminal, 7, 19, "placement-only reuse");
        assertText(terminal, 17, 19, "y=170 h=255 r=3");
        assertText(terminal, 19, 19, "no retransmission");
        assertText(terminal, 23, 19, "delete d=a kept data");
        assertText(terminal, 25, 19, "y=425 h=255 r=3");
        assertText(terminal, 28, 2, "same fixture / before and after");
        assertText(terminal, 31, 2, "$ ready");
        assertEquals(30, terminal.getCursorRow());
        assertEquals(8, terminal.getCursorCol());

        Bitmap bitmap = Bitmap.createBitmap(width, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(terminal.getRendererState().getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
        canvas.save();
        canvas.translate(PADDING, PADDING);
        renderer.render(terminal, canvas, 0, -1, -1, -1, -1);
        canvas.restore();
        return bitmap;
    }

    private static void append(TerminalEmulator terminal, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // Deliberately fragment production output like PTY reads, including inside APC headers,
        // encoded PNG payload, and final ST. No protocol command is reconstructed here.
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

    private static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        @Override public void write(byte[] data, int offset, int count) { replies.write(data, offset, count); }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
}
