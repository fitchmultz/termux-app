package com.termux.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Base64;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Native BitmapFactory/setPixels/Canvas and OSC coverage on API 28/37; API 24 is parser-only. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class ChafaTiffDecoderTest {
    private static final String ST = "\033\\";
    private static final class Logs extends TermuxTerminalSessionClientBase {
        final StringBuilder messages = new StringBuilder();
        @Override public void logError(String tag, String message) { messages.append(message).append('\n'); }
        @Override public void logWarn(String tag, String message) { messages.append(message).append('\n'); }
    }
    private final Logs logs = new Logs();

    private TerminalBuffer screen() { return new TerminalBuffer(logs, 40, 36, 12); }

    /**
     * Wire-layout fixture reconstructed from Chafa 1.18.3's unchanged production encoder, NOT a
     * claimed capture from running Chafa. Source pin/blob provenance is in chafa-tiff-source-proof.json.
     * Keep the omitted Compression tag, RGBA bytes at 8, trailing IFD and external four SHORTs.
     */
    private static byte[] tiff(int width, int height, int[] argb, boolean compressionTag) {
        int count = compressionTag ? 12 : 11;
        int directory = 8 + width * height * 4;
        int end = directory + 2 + count * 12 + 4;
        ByteBuffer bytes = ByteBuffer.allocate(end + 8).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x002a4949).putInt(directory);
        for (int color : argb) {
            bytes.put((byte) (color >>> 16)).put((byte) (color >>> 8))
                .put((byte) color).put((byte) (color >>> 24));
        }
        bytes.putShort((short) count);
        tag(bytes, 256, 4, 1, width);
        tag(bytes, 257, 4, 1, height);
        tag(bytes, 258, 3, 4, end);
        if (compressionTag) tag(bytes, 259, 3, 1, 1);
        tag(bytes, 262, 3, 1, 2);
        tag(bytes, 273, 4, 1, 8);
        tag(bytes, 274, 3, 1, 1);
        tag(bytes, 277, 3, 1, 4);
        tag(bytes, 278, 4, 1, height);
        tag(bytes, 279, 4, 1, width * height * 4);
        tag(bytes, 284, 3, 1, 1);
        tag(bytes, 338, 3, 1, 2);
        bytes.putInt(0);
        for (int i = 0; i < 4; i++) bytes.putShort((short) 8);
        assertEquals(bytes.capacity(), bytes.position());
        return bytes.array();
    }

    private static void tag(ByteBuffer bytes, int id, int type, int count, int value) {
        bytes.putShort((short) id).putShort((short) type).putInt(count).putInt(value);
    }

    private static byte[] colors() {
        return tiff(2, 2, new int[] {Color.RED, Color.GREEN, 0x800000ff, 0x0000ff00}, false);
    }

    private static byte[] solid(int color) {
        int[] pixels = new int[16 * 16];
        Arrays.fill(pixels, color);
        return tiff(16, 16, pixels, false);
    }

    private static ByteBuffer le(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    private static int entry(byte[] bytes, int id) {
        ByteBuffer data = le(bytes);
        int directory = data.getInt(4);
        int count = data.getShort(directory) & 0xffff;
        for (int i = 0; i < count; i++) {
            int position = directory + 2 + 12 * i;
            if ((data.getShort(position) & 0xffff) == id) return position;
        }
        throw new AssertionError("Missing fixture tag " + id);
    }

    private static byte[] value(int tag, int value) {
        byte[] bytes = colors();
        le(bytes).putInt(entry(bytes, tag) + 8, value);
        return bytes;
    }

    /** Robolectric 4.17 selects legacy graphics below API 26: do not claim pixel coverage here. */
    @Test @Config(sdk = 24) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void parserOnlyOnOldestSupportedApiDoesNotAllocateOrUseLegacyPixels() {
        ChafaTiffDecoder source = ChafaTiffDecoder.parse(colors());
        assertNotNull(source);
        assertEquals(2, source.width);
        assertEquals(2, source.height);
        assertNull(ChafaTiffDecoder.parse(new byte[14]));
        assertNull(ChafaTiffDecoder.parse(value(256, -1)));
        assertNull(ChafaTiffDecoder.parse(value(279, 15)));
    }

    @Test public void sourcePinnedChafaRgbaAndDefaultCompressionDecode() {
        ChafaTiffDecoder source = ChafaTiffDecoder.parse(colors());
        assertNotNull(source);
        assertEquals(2, source.width);
        assertEquals(2, source.height);
        Bitmap bitmap = source.decode(1);
        assertNotNull(bitmap);
        assertTrue(bitmap.isPremultiplied());
        assertEquals(Color.RED, bitmap.getPixel(0, 0));
        assertEquals(Color.GREEN, bitmap.getPixel(1, 0));
        assertEquals(0x800000ff, bitmap.getPixel(0, 1));
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(1, 1));
        ChafaTiffDecoder explicit = ChafaTiffDecoder.parse(tiff(1, 1, new int[] {Color.RED}, true));
        assertNotNull(explicit);
        assertEquals(Color.RED, explicit.decode(1).getPixel(0, 0));
        byte[] reordered = tiff(2, 1, new int[] {Color.RED, Color.GREEN}, false);
        int first = entry(reordered, 256), second = entry(reordered, 257);
        byte[] firstEntry = Arrays.copyOfRange(reordered, first, first + 12);
        System.arraycopy(reordered, second, reordered, first, 12);
        System.arraycopy(firstEntry, 0, reordered, second, 12);
        ChafaTiffDecoder equivalent = ChafaTiffDecoder.parse(reordered);
        assertNotNull(equivalent);
        assertEquals(2, equivalent.width);
        assertEquals(1, equivalent.height);
        assertEquals(Color.GREEN, equivalent.decode(1).getPixel(1, 0));
    }

    @Test public void unassociatedAlphaCompositesCorrectlyOnNativeCanvas() {
        Bitmap bitmap = ChafaTiffDecoder.parse(colors()).decode(1);
        for (int background : new int[] {Color.BLACK, Color.WHITE}) {
            Bitmap result = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(background);
            canvas.drawBitmap(bitmap, new Rect(0, 0, 2, 2), new Rect(0, 0, 2, 2), null);
            assertEquals(Color.RED, result.getPixel(0, 0));
            assertEquals(Color.GREEN, result.getPixel(1, 0));
            int blue = result.getPixel(0, 1);
            assertEquals(255, Color.alpha(blue));
            assertEquals(background == Color.BLACK ? 0 : 127, Color.red(blue), 1);
            assertEquals(background == Color.BLACK ? 0 : 127, Color.green(blue), 1);
            assertEquals(background == Color.BLACK ? 128 : 255, Color.blue(blue), 1);
            assertEquals(background, result.getPixel(1, 1));
        }
    }

    @Test public void samplingAllocatesOnlySampledGeometryAndRetainsPartialEdgeSamples() {
        int[] pixels = new int[9 * 5];
        for (int y = 0; y < 5; y++) for (int x = 0; x < 9; x++)
            pixels[y * 9 + x] = Color.rgb(x * 25, y * 50, 17);
        byte[] bytes = tiff(9, 5, pixels, false);
        byte[] original = bytes.clone();
        ChafaTiffDecoder source = ChafaTiffDecoder.parse(bytes);
        Bitmap sampled = source.decode(4);
        assertEquals(3, sampled.getWidth());
        assertEquals(2, sampled.getHeight());
        for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++)
            assertEquals(pixels[y * 4 * 9 + x * 4], sampled.getPixel(x, y));
        Bitmap smallest = source.decode(Integer.MAX_VALUE);
        assertEquals(1, smallest.getWidth());
        assertEquals(1, smallest.getHeight());
        assertEquals(pixels[0], smallest.getPixel(0, 0));
        assertArrayEquals(original, bytes);
        try { source.decode(0); fail("Zero sample accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void terminalBuildUsesExistingSamplingAndPaddingPipeline() {
        int[] pixels = new int[64 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++)
            pixels[y * 64 + x] = Color.rgb(x * 4, y * 8, 17);
        TerminalBitmap image = TerminalBitmap.build(screen(), 0, tiff(64, 32, pixels, false),
            0, 0, 1, 1, 8, 4, false);
        assertNotNull(image);
        assertEquals(8, image.getBitmap().getWidth());
        assertEquals(4, image.getBitmap().getHeight());
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++)
            assertEquals(pixels[y * 8 * 64 + x * 8], image.getBitmap().getPixel(x, y));
        TerminalBitmap padded = TerminalBitmap.build(screen(), 1, colors(),
            0, 0, 4, 4, -1, -1, true);
        assertNotNull(padded);
        assertEquals(4, padded.getBitmap().getWidth());
        assertEquals(4, padded.getBitmap().getHeight());
        assertEquals(Color.RED, padded.getBitmap().getPixel(0, 0));
        assertEquals(Color.TRANSPARENT, padded.getBitmap().getPixel(3, 3));
    }

    @Test(timeout = 10000) public void invalidEmptyAndOverflowingHeadersNeverChangeCells() {
        byte[] empty = new byte[14];
        le(empty).putInt(0x002a4949).putInt(8); // Empty IFD: upstream's sampling loop never terminated.
        byte[] huge = colors();
        le(huge).putInt(entry(huge, 256) + 8, 16384);
        le(huge).putInt(entry(huge, 257) + 8, 16384);
        le(huge).putInt(entry(huge, 278) + 8, 16384);
        le(huge).putInt(entry(huge, 279) + 8, 1024 * 1024 * 1024);
        byte[] offset = colors();
        le(offset).putInt(4, -1);
        byte[] count = colors();
        le(count).putShort(le(count).getInt(4), (short) 0xffff);
        byte[] overflow = value(256, -1);
        le(overflow).putInt(entry(overflow, 257) + 8, -1);
        for (byte[] bytes : new byte[][] {empty, huge, offset, count, overflow, value(256, 0),
            value(257, 0), value(273, -1), value(279, -1), value(279, 15), value(258, -1)}) {
            assertNull(ChafaTiffDecoder.parse(bytes));
            TerminalBuffer screen = screen();
            screen.setChar(0, 0, 'X', 0);
            long style = screen.getStyleAt(0, 0);
            assertNull(TerminalBitmap.build(screen, 0, bytes, 0, 0, 1, 1, 2, 2, true));
            assertEquals(style, screen.getStyleAt(0, 0));
            assertEquals("X", screen.getSelectedText(0, 0, 0, 0));
        }
        byte[] valid = colors();
        for (int length = 0; length < valid.length; length++)
            assertNull("Truncated TIFF length " + length, ChafaTiffDecoder.parse(Arrays.copyOf(valid, length)));
    }

    @Test public void otherTiffLayoutsAndDngTagsAreNotClaimedByFallback() {
        for (byte[] bytes : new byte[][] {value(262, 3), value(274, 6), value(277, 3),
            value(278, 1), value(284, 2), value(338, 1), value(273, 9)})
            assertNull(ChafaTiffDecoder.parse(bytes));
        byte[] compressed = tiff(1, 1, new int[] {Color.RED}, true);
        le(compressed).putInt(entry(compressed, 259) + 8, 5);
        assertNull(ChafaTiffDecoder.parse(compressed));
        byte[] bits = colors();
        le(bits).putShort(le(bits).getInt(entry(bits, 258) + 8) + 2, (short) 16);
        assertNull(ChafaTiffDecoder.parse(bits));
        byte[] strips = colors();
        le(strips).putInt(entry(strips, 273) + 4, 2);
        assertNull(ChafaTiffDecoder.parse(strips));
        byte[] type = colors();
        le(type).putShort(entry(type, 256) + 2, (short) 3);
        assertNull(ChafaTiffDecoder.parse(type));
        byte[] duplicate = colors();
        le(duplicate).putShort(entry(duplicate, 257), (short) 256);
        assertNull(ChafaTiffDecoder.parse(duplicate));
        byte[] next = colors();
        le(next).putInt(next.length - 12, 8);
        assertNull(ChafaTiffDecoder.parse(next));
        byte[] dngTag = colors();
        le(dngTag).putShort(entry(dngTag, 338), (short) 50706); // DNGVersion: not our subset.
        assertNull(ChafaTiffDecoder.parse(dngTag));
        byte[] bigEndian = colors();
        bigEndian[0] = 'M'; bigEndian[1] = 'M'; bigEndian[2] = 0; bigEndian[3] = 42;
        assertNull(ChafaTiffDecoder.parse(bigEndian));
        byte[] bigTiff = colors();
        bigTiff[2] = 43;
        assertNull(ChafaTiffDecoder.parse(bigTiff));
    }

    @Test public void nativeFactoryFormatsStillDecodeWithoutTiffFallback() {
        Bitmap source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.GREEN);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        byte[] png = bytes.toByteArray();
        assertNull(ChafaTiffDecoder.parse(png));
        TerminalBitmap image = TerminalBitmap.build(screen(), 0, png, 0, 0, 1, 1, -1, -1, true);
        assertNotNull(image);
        assertEquals(Color.GREEN, image.getBitmap().getPixel(0, 0));
    }

    private TerminalEmulator terminal() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new TerminalEmulator(output, 40, 12, 8, 16, 100, logs);
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private static String file(byte[] bytes) {
        return "\033]1337;File=inline=1;width=2;height=1;preserveAspectRatio=0:" +
            Base64.encodeToString(bytes, Base64.NO_WRAP) + ST;
    }

    private static long imageStyle(TerminalBuffer screen, int row) {
        long style = screen.getStyleAt(row, 0);
        assertTrue(TextStyle.isTerminalBitmap(style));
        return style;
    }

    @Test public void singleAndMultipartOscTiffReachNativeTerminalPipeline() {
        TerminalEmulator terminal = terminal();
        append(terminal, file(solid(Color.RED)) + "AFTER");
        Bitmap first = terminal.getScreen().getSixelBitmap(imageStyle(terminal.getScreen(), 0));
        assertEquals(Color.RED, first.getPixel(0, 0));
        assertEquals("AFTER", terminal.getScreen().getSelectedText(2, 0, 6, 0));
        append(terminal, "\033[4;1H\033]1337;MultipartFile=inline=1;width=2;height=1;preserveAspectRatio=0\007");
        String data = Base64.encodeToString(solid(Color.GREEN), Base64.NO_WRAP);
        String multipart = "\033]1337;FilePart=" + data.substring(0, 13) + "\007" +
            "\033]1337;FilePart=" + data.substring(13) + ST + "\033]1337;FileEnd\007";
        for (int i = 0; i < multipart.length(); i++) append(terminal, multipart.substring(i, i + 1));
        Bitmap second = terminal.getScreen().getSixelBitmap(imageStyle(terminal.getScreen(), 3));
        assertEquals(Color.GREEN, second.getPixel(0, 0));
        append(terminal, "\033]1337;File=inline=1;width=2px:" +
            Base64.encodeToString(new byte[14], Base64.NO_WRAP) + "\007SAFE");
        assertTrue(terminal.getScreen().getTranscriptText().contains("SAFE"));
    }

    @Test public void publishedTiffSurvivesSnapshotCollectionReplacementAndResize() {
        TerminalEmulator terminal = terminal();
        append(terminal, file(solid(Color.RED)));
        TerminalBuffer live = terminal.getScreen();
        long style = imageStyle(live, 0);
        Bitmap pixels = live.getSixelBitmap(style);
        live.clearTranscript();
        assertSame(pixels, live.getSixelBitmap(style));
        append(terminal, "\033[?2026h");
        TerminalBuffer snapshot = terminal.getRendererState().getScreen();
        append(terminal, "\033[2J\033[H");
        live.doTerminalBitmapsGC(0);
        assertNull(live.getSixelBitmap(style));
        append(terminal, file(solid(Color.BLUE)));
        assertSame(pixels, snapshot.getSixelBitmap(style));
        assertEquals(Color.RED, pixels.getPixel(0, 0));
        assertFalse(pixels.isRecycled());
        terminal.resize(30, 12, 9, 18);
        assertSame(pixels, snapshot.getSixelBitmap(style));
        for (int row = 0; row < 12; row++) for (int col = 0; col < 30; col++)
            assertFalse(TextStyle.isTerminalBitmap(terminal.getScreen().getStyleAt(row, col)));
        append(terminal, "\033[?2026lSAFE");
        assertTrue(terminal.getScreen().getTranscriptText().contains("SAFE"));
        assertFalse(pixels.isRecycled());
        assertEquals("", logs.messages.toString());
    }
}
