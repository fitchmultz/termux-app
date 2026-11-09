package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Direct-data frontend through production Android decoding and the shared image-cell backend. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalKittyGraphicsTest {
    private static final String ST = "\033\\";
    private static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        @Override public void write(byte[] data, int offset, int count) { replies.write(data, offset, count); }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
        String take() {
            String result = replies.toString(StandardCharsets.UTF_8);
            replies.reset();
            return result;
        }
    }
    private static TerminalEmulator terminal(Output output) {
        TerminalEmulator terminal = new TerminalEmulator(output, 10, 6, 8, 16, 100, null);
        append(terminal, "\033[?25l");
        return terminal;
    }
    private static void append(TerminalEmulator terminal, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }
    private static String command(String controls) { return "\033_G" + controls + ST; }
    private static String raw(String controls, int... rgba) {
        byte[] bytes = new byte[rgba.length];
        for (int i = 0; i < rgba.length; i++) bytes[i] = (byte) rgba[i];
        return command(controls + ";" + Base64.encodeToString(bytes, Base64.NO_WRAP));
    }
    private static String red(String controls) { return raw(controls + ",s=1,v=1", 255, 0, 0, 255); }
    private static String png() {
        Bitmap image = Bitmap.createBitmap(new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff},
            2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }
    private static Bitmap pixels(TerminalEmulator terminal, int row, int column) {
        long style = terminal.getScreen().getStyleAt(row, column);
        assertTrue("Expected bitmap at " + row + "," + column, TextStyle.isTerminalBitmap(style));
        Bitmap pixels = terminal.getScreen().getSixelBitmap(style);
        assertNotNull(pixels);
        return pixels;
    }
    private static void noImage(TerminalEmulator terminal) {
        for (int row = 0; row < terminal.getRows(); row++)
            for (int col = 0; col < terminal.getColumns(); col++)
                assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(row, col)));
    }

    @Test public void actualPinnedPiUploadCropDeleteReuseReachesAndroidParserAndRenderer() throws Exception {
        Output output = new Output();
        TerminalEmulator terminal = new TerminalEmulator(output, 80, 40, 8, 16, 100, null);
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/pi-kitty-preview.ansi")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        terminal.append(fixture, fixture.length);
        assertEquals(0, output.replies.size());
        assertEquals(88, pixels(terminal, 6, 2).getWidth());
        assertEquals(128, pixels(terminal, 6, 2).getHeight());
        assertEquals(48, pixels(terminal, 16, 2).getHeight());
        assertEquals(48, pixels(terminal, 22, 2).getHeight());
        // The unchanged capture fixture checks its exact source hash/text/cursor on both heads.
        // Here only the candidate must show pixels in an otherwise text-free preview rectangle.
        Bitmap frame = TerminalKittyImageRenderingTest.renderWorkbench(480);
        TerminalRenderer renderer = new TerminalRenderer(16, Typeface.MONOSPACE);
        int changed = 0;
        for (int y = 12 + 7 * renderer.getFontLineSpacing(); y < 12 + 13 * renderer.getFontLineSpacing(); y++)
            for (int x = 12 + (int) Math.ceil(3 * renderer.getFontWidth());
                 x < 12 + (int) Math.floor(12 * renderer.getFontWidth()); x++)
                if (frame.getPixel(x, y) != 0xff101923) changed++;
        assertTrue("Actual Pi pixels must reach the native renderer, not only be swallowed as APC", changed > 100);
    }

    @Test public void defaultTransmitAndRawRgbRgbaUseActualChannelOrder() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, raw("i=1,f=24,s=2,v=1", 255, 0, 0, 0, 255, 0));
        noImage(terminal); // Omitted a is t, not T.
        assertEquals("\033_Gi=1;OK" + ST, output.take());
        append(terminal, command("a=p,i=1,C=1"));
        Bitmap rgb = pixels(terminal, 0, 0);
        assertEquals(0xffff0000, rgb.getPixel(0, 0));
        assertEquals(0xff00ff00, rgb.getPixel(1, 0));
        append(terminal, "\033[3;1H" + raw("a=T,i=2,s=1,v=1,C=1", 0, 0, 255, 128));
        int rgba = pixels(terminal, 2, 0).getPixel(0, 0);
        assertEquals(128, rgba >>> 24);
        assertEquals(255, rgba & 255);
        assertEquals(2, terminal.getCursorRow());
        assertEquals(0, terminal.getCursorCol());
    }

    @Test public void canonicalQueryValidatesWithoutStoringAndRepliesBeforeDeviceAttributes() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, command("i=31,s=1,v=1,a=q,t=d,f=24;AAAA") + "\033[c");
        assertTrue(output.take().startsWith("\033_Gi=31;OK" + ST + "\033[?"));
        noImage(terminal);
        append(terminal, command("a=p,i=31"));
        assertTrue(output.take().contains("ENOENT"));
        append(terminal, command("a=q,f=100,i=32;AAAA"));
        assertTrue(output.take().contains("EBADDATA"));
        append(terminal, red("a=q,i=33,q=1"));
        assertEquals("", output.take());
        append(terminal, command("t=f,a=T,i=34,q=2;L3ByaXZhdGU="));
        assertEquals("", output.take());
        noImage(terminal);
    }

    @Test public void chunkedPngRetainsFirstControlsUnsignedIdsAndQuietAcrossByteReads() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        String image = png();
        append(terminal, command("a=T,f=100,i=4294967295,p=4294967295,q=2,C=1,c=2,r=1,m=1;" + image.substring(0, 13)));
        noImage(terminal);
        String finalChunk = command("m=0;" + image.substring(13));
        for (int i = 0; i < finalChunk.length(); i++) append(terminal, finalChunk.substring(i, i + 1));
        assertEquals("", output.take());
        assertEquals(0, terminal.getCursorRow());
        assertEquals(0, terminal.getCursorCol());
        assertEquals(0xffff0000, pixels(terminal, 0, 0).getPixel(2, 2));
        append(terminal, "\033[3;1H" + command("a=p,i=4294967295,p=4294967295,C=1,c=2,r=1"));
        assertEquals("\033_Gi=4294967295,p=4294967295;OK" + ST, output.take());
        assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(0, 0)));
        assertNotNull(pixels(terminal, 2, 0));
    }

    @Test public void pngCropUsesSourcePixelsNotDeclaredSizeAndChecksScaleBeforeAllocation() {
        // The same crop/filter path handles all formats. PNG s/v deliberately disagree with its
        // decoded 2x2 source; raw RGB/RGBA must declare their actual dimensions.
        String[] uploads = {
            command("a=t,f=100,i=1,s=1,v=1;" + png()),
            raw("a=t,f=24,i=1,s=2,v=2", 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255),
            raw("a=t,f=32,i=1,s=2,v=2", 255, 0, 0, 255, 0, 255, 0, 255,
                0, 0, 255, 255, 255, 255, 255, 255)
        };
        for (String upload : uploads) {
            Output output = new Output();
            TerminalEmulator terminal = terminal(output);
            append(terminal, upload);
            output.take();
            append(terminal, command("a=p,i=1,x=1,y=0,w=99,h=1,c=1,r=1,C=1"));
            Bitmap crop = pixels(terminal, 0, 0);
            assertEquals(8, crop.getWidth());
            assertEquals(16, crop.getHeight());
            // The selected one green pixel must not blend with red/blue/white outside its crop.
            // An 8x8 aspect fit is vertically centered in the requested 8x16 rectangle.
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 8; x++)
                    assertEquals("Crop pixel " + x + "," + y,
                        y >= 4 && y < 12 ? 0xff00ff00 : 0, crop.getPixel(x, y));
            long style = terminal.getScreen().getStyleAt(0, 0);
            append(terminal, command("a=p,i=1,c=2048,r=1024,C=1"));
            assertTrue(output.take().contains("EINVAL"));
            assertEquals(style, terminal.getScreen().getStyleAt(0, 0));
            append(terminal, "SAFE");
            assertEquals("SAFE", terminal.getScreen().getSelectedText(0, 0, 3, 0));
            // Crop isolation must leave the cached original pixels available for later reuse.
            append(terminal, "\033[3;1H" + command("a=p,i=1,C=1"));
            Bitmap original = pixels(terminal, 2, 0);
            assertEquals(0xffff0000, original.getPixel(0, 0));
            assertEquals(0xff00ff00, original.getPixel(1, 0));
            assertEquals(0xff0000ff, original.getPixel(0, 1));
            assertEquals(0xffffffff, original.getPixel(1, 1));
        }
    }

    @Test public void namedPlacementReplacementDeleteAndReuseKeepOtherReferences() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, red("a=t,i=7,q=2"));
        append(terminal, command("a=p,i=7,p=1,C=1,q=2"));
        append(terminal, "\033[3;1H" + command("a=p,i=7,p=2,C=1,q=2"));
        append(terminal, "\033[5;1H" + command("a=p,i=7,p=1,C=1,q=2"));
        assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(0, 0)));
        append(terminal, command("a=d,d=I,i=7,p=1,q=2"));
        assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(4, 0)));
        assertNotNull(pixels(terminal, 2, 0));
        append(terminal, command("a=p,i=7,p=3,C=1"));
        assertTrue(output.take().endsWith(";OK" + ST));
        append(terminal, command("a=d,d=I,i=7,q=2") + command("a=p,i=7"));
        noImage(terminal);
        assertTrue(output.take().contains("ENOENT"));
    }

    @Test public void retransmissionReplacesPlacementsButQueriesAndAnonymousIdsDoNot() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, red("a=T,i=1,p=0,C=1,q=2"));
        append(terminal, red("a=q,i=1,q=2"));
        assertNotNull(pixels(terminal, 0, 0));
        append(terminal, raw("a=t,i=1,s=1,v=1,q=2", 0, 255, 0, 255));
        noImage(terminal);
        append(terminal, command("a=p,i=1,p=0,C=1,q=2"));
        assertEquals(0xff00ff00, pixels(terminal, 0, 0).getPixel(0, 0));
        append(terminal, "\033[3;1H" + red("a=T,i=0,p=4,C=1,q=2"));
        append(terminal, "\033[5;1H" + red("a=T,i=0,p=4,C=1,q=2"));
        assertNotNull(pixels(terminal, 2, 0));
        assertNotNull(pixels(terminal, 4, 0));
    }

    @Test public void visibleDeletesRetainHistoryAndLowercaseAllowsPiCacheReuse() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, red("a=T,i=1,C=1,q=2") + "\r\n".repeat(6));
        append(terminal, red("a=T,i=2,C=1,q=2"));
        append(terminal, command("a=d,d=a,q=2"));
        noImage(terminal);
        append(terminal, command("a=p,i=2,C=1"));
        assertTrue(output.take().endsWith(";OK" + ST));
        append(terminal, command("a=d,d=A,q=2") + command("a=p,i=2"));
        assertTrue(output.take().contains("ENOENT"));
        append(terminal, command("a=p,i=1,C=1"));
        assertTrue(output.take().endsWith(";OK" + ST));
    }

    @Test public void completedFrameOwnsPixelsThroughDeleteReplaceAndResize() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, red("a=T,i=1,C=1,q=2"));
        long style = terminal.getScreen().getStyleAt(0, 0);
        Bitmap original = pixels(terminal, 0, 0);
        append(terminal, "\033[?2026h" + command("a=d,d=I,i=1,q=2"));
        TerminalRendererState completed = terminal.getRendererState();
        assertSame(original, completed.getScreen().getSixelBitmap(style));
        assertFalse(original.isRecycled());
        append(terminal, raw("a=T,i=1,C=1,q=2,s=1,v=1", 0, 0, 255, 255));
        assertSame(original, completed.getScreen().getSixelBitmap(style));
        terminal.resize(12, 6, 9, 18);
        noImage(terminal);
        assertEquals(0xffff0000, original.getPixel(0, 0));
        append(terminal, "\033[?2026l" + command("a=p,i=1,C=1,q=2"));
        assertEquals(0xff0000ff, pixels(terminal, 0, 0).getPixel(0, 0));
    }

    @Test public void placementUsesLrMarginScrollWithoutMovingTranscriptOrOutsideText() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, "LEFT\033[?69h\033[2;6s\033[2;5r\033[4;2HKEEP\033[5;2H");
        append(terminal, red("a=T,i=1,c=2,r=3,C=1,q=2"));
        assertEquals(0, terminal.getScrollCounter());
        assertEquals(0, terminal.getScreen().getActiveTranscriptRows());
        assertEquals("KEEP", terminal.getScreen().getSelectedText(1, 3, 4, 3));
        assertEquals("L", terminal.getScreen().getSelectedText(0, 0, 0, 0));
        assertEquals(4, terminal.getCursorRow());
        assertEquals(1, terminal.getCursorCol());
        assertNotNull(pixels(terminal, 4, 1));
        append(terminal, "\033[2;1H" + red("a=T,i=2,c=1,r=3,C=1,q=2"));
        assertEquals(0, terminal.getScrollCounter());
        assertNotNull(pixels(terminal, 1, 0));
    }

    @Test public void normalPlacementCursorAndFullWidthScrollAreAccountedOnce() {
        TerminalEmulator terminal = terminal(new Output());
        append(terminal, red("a=T,c=2,r=2,q=2"));
        assertEquals(1, terminal.getCursorRow());
        assertEquals(2, terminal.getCursorCol());
        append(terminal, "\033[6;1H" + red("a=T,c=2,r=3,q=2"));
        assertEquals(2, terminal.getScrollCounter());
        assertEquals(2, terminal.getScreen().getActiveTranscriptRows());
        assertEquals(5, terminal.getCursorRow());
        assertEquals(2, terminal.getCursorCol());
        TerminalEmulator anchored = terminal(new Output());
        append(anchored, "\033[5;1HKEEP\033[6;1H" + red("a=T,c=2,r=3,C=1,q=2"));
        assertEquals("KEEP", anchored.getScreen().getSelectedText(0, 4, 3, 4));
        assertEquals(0, anchored.getScrollCounter());
        assertEquals(0, anchored.getScreen().getActiveTranscriptRows());
        assertEquals(5, anchored.getCursorRow());
        assertEquals(0, anchored.getCursorCol());
    }

    @Test public void cancellationsInvalidDataAndUnsupportedTransportsDrainThenRecover() {
        Output output = new Output();
        TerminalEmulator terminal = terminal(output);
        append(terminal, command("a=T,f=100,i=1,m=1;AAAA") + command("a=d,q=2"));
        append(terminal, command("m=0;AAAA"));
        append(terminal, "\033_Ga=T,f=100,i=2;AAAA\030");
        append(terminal, command("a=T,t=s,i=3,q=2;AAAA") + command("a=T,U=1,i=4,q=2;AAAA"));
        append(terminal, command("a=T,f=100,i=5,q=2;A===") + "SAFE");
        noImage(terminal);
        assertEquals("SAFE", terminal.getScreen().getTranscriptText());
        append(terminal, red("a=T,i=6,C=1,q=2"));
        assertNotNull(pixels(terminal, 0, 4));
        append(terminal, "\033c" + command("a=p,i=6"));
        assertTrue(output.take().contains("ENOENT"));
    }
}
