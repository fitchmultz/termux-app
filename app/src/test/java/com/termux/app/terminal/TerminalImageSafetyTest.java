package com.termux.app.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.util.Base64;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.ITermImage;
import com.termux.terminal.TerminalBitmap;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSixel;
import com.termux.terminal.TextStyle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

/** Native BitmapFactory/Canvas coverage; deliberately does not replace graphics with shadows. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class TerminalImageSafetyTest {
    private static final class Logs extends TermuxTerminalSessionClientBase {
        final StringBuilder messages = new StringBuilder();
        @Override public void logError(String tag, String message) { messages.append(message).append('\n'); }
        @Override public void logWarn(String tag, String message) { messages.append(message).append('\n'); }
    }

    private static final class Image extends ITermImage {
        Image(Logs logs, boolean multipart) { super(logs, multipart); }
        int encodedLength() { return mEncodedImage.length(); }
        int encodedCapacity() { return mEncodedImage.capacity(); }
    }

    private static final class MarginBuffer extends TerminalBuffer {
        final boolean scroll;
        int scrolls;
        MarginBuffer(Logs logs, boolean scroll) {
            super(logs, 5, 30, 6);
            this.scroll = scroll;
            blockSet(0, 0, 5, 6, 'P', 0);
            blockSet(0, 0, 5, 1, 'H', 0);
            blockSet(0, 4, 5, 2, 'F', 0);
        }
        @Override public int getImageRightMargin() { return 3; }
        @Override public int getImageBottomMargin() { return 4; }
        @Override public boolean canScrollImage() { return scroll; }
        @Override public boolean scrollForImage() {
            if (!scroll) return false;
            // Match the emulator's LR-margin scrolling, preserving all surrounding cells.
            blockCopy(1, 2, 2, 2, 1, 1);
            blockSet(1, 3, 2, 1, ' ', 0);
            scrolls++;
            return true;
        }
    }

    private final Logs logs = new Logs();

    private TerminalBuffer screen(int columns, int rows) {
        return new TerminalBuffer(logs, columns, rows + 24, rows);
    }

    private TerminalEmulator emulator() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
        return new TerminalEmulator(output, 80, 24, 8, 16, 100, logs);
    }

    private static Bitmap colors() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.setPixel(0, 0, Color.RED);
        bitmap.setPixel(1, 0, Color.GREEN);
        bitmap.setPixel(0, 1, Color.BLUE);
        bitmap.setPixel(1, 1, Color.YELLOW);
        return bitmap;
    }

    private static byte[] png(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        return bytes.toByteArray();
    }

    @Test
    public void checkedLongDimensionsRespectAndroidDrawLimits() {
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ?
            150 * 1024 * 1024 : 100 * 1024 * 1024, TerminalBitmap.MAX_BITMAP_SIZE);
        assertTrue(TerminalBitmap.isBitmapSizeValid(1, 1));
        assertFalse(TerminalBitmap.isBitmapSizeValid(0, 1));
        assertFalse(TerminalBitmap.isBitmapSizeValid(1, -1));
        assertFalse(TerminalBitmap.isBitmapSizeValid(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(TerminalBitmap.isBitmapSizeValid(Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(TerminalBitmap.isBitmapSizeValid(TerminalBitmap.MAX_BITMAP_DIMENSION + 1L, 1));
        int width = 4096;
        int height = TerminalBitmap.MAX_BITMAP_SIZE / (width * 4);
        assertTrue(TerminalBitmap.isBitmapSizeValid(width, height));
        assertFalse(TerminalBitmap.isBitmapSizeValid(width, height + 1L));
    }

    @Test
    public void resizeCopiesPixelsAndPadsTransparentlyWithoutChangingSource() {
        Bitmap source = colors();
        source.setDensity(1); // Copy geometry must not depend on source/display density.
        Bitmap padded = TerminalBitmap.resizeBitmap("test", "image", logs, source, 4, 4);
        assertNotNull(padded);
        assertEquals(Color.RED, padded.getPixel(0, 0));
        assertEquals(Color.YELLOW, padded.getPixel(1, 1));
        assertEquals(Color.TRANSPARENT, padded.getPixel(3, 3));
        assertEquals(2, source.getWidth());
        assertEquals(Color.GREEN, source.getPixel(1, 0));
        Bitmap cropped = TerminalBitmap.resizeBitmap("test", "image", logs, source, 1, 2);
        assertNotNull(cropped);
        assertEquals(Color.BLUE, cropped.getPixel(0, 1));
        assertNull(TerminalBitmap.resizeBitmap("test", "image", logs, source, 65536, 65536));
        assertNull(TerminalBitmap.resizeBitmapConstrained("test", "image", logs, source,
            2, 2, Integer.MAX_VALUE, Integer.MAX_VALUE, 80));
        assertNull(TerminalBitmap.resizeBitmapConstrained("test", "image", logs, source, 2, 2, 0, 1, 80));
        assertFalse(source.isRecycled());
    }

    @Test
    public void imageDecodePadsScalesAndClipsTheVisibleLeftPortion() {
        byte[] image = png(colors());
        TerminalBuffer buffer = screen(4, 8);
        TerminalBitmap padded = TerminalBitmap.build(buffer, 65535, image, 0, 0, 4, 4, -1, -1, true);
        assertNotNull(padded);
        assertEquals(4, padded.getBitmap().getWidth());
        assertEquals(4, padded.getBitmap().getHeight());
        assertEquals(Color.YELLOW, padded.getBitmap().getPixel(1, 1));
        assertEquals(Color.TRANSPARENT, padded.getBitmap().getPixel(3, 3));
        assertArrayEquals(new int[] {1, 1}, padded.getCursorDelta());
        assertEquals(65535, TextStyle.getTerminalBitmapNum(buffer.getStyleAt(0, 0)));

        TerminalBitmap preserve = TerminalBitmap.build(buffer, 0, image, 0, 1, 2, 2, 8, 4, true);
        assertNotNull(preserve);
        assertEquals(4, preserve.getBitmap().getWidth());
        assertEquals(4, preserve.getBitmap().getHeight());
        TerminalBitmap stretch = TerminalBitmap.build(buffer, 1, image, 0, 1, 2, 2, 8, 4, false);
        assertNotNull(stretch);
        assertEquals(8, stretch.getBitmap().getWidth());
        assertEquals(4, stretch.getBitmap().getHeight());

        Bitmap stripes = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        stripes.eraseColor(Color.GREEN);
        stripes.setPixel(0, 0, Color.RED);
        stripes.setPixel(1, 0, Color.RED);
        stripes.setPixel(0, 1, Color.RED);
        stripes.setPixel(1, 1, Color.RED);
        TerminalBitmap clipped = TerminalBitmap.build(buffer, 2, png(stripes), 3, 0, 2, 2, -1, -1, true);
        assertNotNull(clipped);
        assertEquals(2, clipped.getBitmap().getWidth());
        assertEquals(2, clipped.getBitmap().getHeight());
        assertEquals(Color.RED, clipped.getBitmap().getPixel(1, 1));
    }

    @Test(timeout = 10000)
    public void invalidImageHeadersAndPlacementsFailBeforeChangingCells() {
        TerminalBuffer buffer = screen(4, 4);
        buffer.setChar(0, 0, 'X', 0);
        long originalStyle = buffer.getStyleAt(0, 0);
        // Upstream's requested-size sampling loop could hang when decodeBounds returned -1.
        assertNull(TerminalBitmap.build(buffer, 0, new byte[] {1, 2, 3}, 0, 0, 2, 2, 8, 8, true));
        assertNull(TerminalBitmap.build(buffer, 0, colors(), 0, 0, 0, 2));
        assertNull(TerminalBitmap.build(buffer, 65536, colors(), 0, 0, 2, 2));
        assertNull(TerminalBitmap.build(buffer, 0, colors(), 4, 0, 2, 2));
        assertNull(TerminalBitmap.build(buffer, 0, colors(), 0, -1, 2, 2));
        assertNull(TerminalBitmap.build(buffer, 0, png(colors()), 0, 0, Integer.MAX_VALUE, 2, -1, -1, true));

        // Keep a valid PNG header/CRC but request the full undrawable width. Header
        // preflight must reject it before pixel decode; requested downsampling is separate.
        byte[] hugeHeader = png(colors());
        ByteBuffer.wrap(hugeHeader).putInt(16, 65536);
        CRC32 crc = new CRC32();
        crc.update(hugeHeader, 12, 17);
        ByteBuffer.wrap(hugeHeader).putInt(29, (int) crc.getValue());
        assertNull(TerminalBitmap.build(buffer, 0, hugeHeader, 0, 0, 2, 2, -1, -1, true));
        assertEquals(originalStyle, buffer.getStyleAt(0, 0));
        assertEquals("X", buffer.getSelectedText(0, 0, 0, 0));
    }

    @Test
    public void placementReportsInternalScrollsAndPreservesSixteenBitCellOffsets() {
        TerminalBuffer buffer = screen(2, 2);
        Bitmap tall = Bitmap.createBitmap(1, 4100, Bitmap.Config.ARGB_8888);
        TerminalBitmap image = TerminalBitmap.build(buffer, 65535, tall, 0, 1, 1, 1);
        assertNotNull(image);
        assertEquals(4099, image.getBufferScrollLines());
        assertEquals(1, image.getScrollLines());
        long lastCell = buffer.getStyleAt(1, 0);
        assertEquals(65535, TextStyle.getTerminalBitmapNum(lastCell));
        assertEquals(4099, TextStyle.getTerminalBitmapY(lastCell));
        assertEquals(0, TextStyle.getTerminalBitmapX(lastCell));

        TerminalBuffer wideBuffer = screen(4100, 1);
        Bitmap wide = Bitmap.createBitmap(4100, 1, Bitmap.Config.ARGB_8888);
        assertNotNull(TerminalBitmap.build(wideBuffer, 65535, wide, 0, 0, 1, 1));
        assertEquals(4099, TextStyle.getTerminalBitmapX(wideBuffer.getStyleAt(0, 4099)));
    }

    @Test
    public void sixelPixelsOverprintGrowAndHonorRasterExtents() {
        TerminalSixel sixel = TerminalSixel.build(logs, 1, 1);
        assertNotNull(sixel);
        sixel.setRGBColor(1, 100, 0, 0);
        assertTrue(sixel.readData('~', 2));
        assertEquals(Color.RED, sixel.getBitmap().getPixel(1, 5));
        sixel.setRGBColor(2, 0, 100, 0);
        assertTrue(sixel.readData('$', 1));
        assertTrue(sixel.readData('@', 1));
        assertEquals(Color.GREEN, sixel.getBitmap().getPixel(0, 0));
        assertEquals(Color.RED, sixel.getBitmap().getPixel(0, 1));
        assertTrue(sixel.readData('-', 1));
        assertTrue(sixel.readData('~', 1));
        assertEquals(Color.GREEN, sixel.getBitmap().getPixel(0, 11));
        assertEquals(2, sixel.getWidth());
        assertEquals(12, sixel.getHeight());
        assertTrue(sixel.resize(8, 18));
        TerminalBitmap image = TerminalBitmap.build(screen(8, 8), 0, sixel, 0, 0, 4, 6);
        assertNotNull(image);
        assertEquals(8, image.getBitmap().getWidth());
        assertEquals(18, image.getBitmap().getHeight());
        assertEquals(Color.TRANSPARENT, image.getBitmap().getPixel(7, 17));
    }

    @Test
    public void invalidSixelDimensionsRepeatsAndColorsCannotOverflowOrAllocate() {
        assertNull(TerminalSixel.build(logs, 0, 6));
        assertNull(TerminalSixel.build(logs, Integer.MAX_VALUE, Integer.MAX_VALUE));
        TerminalSixel sixel = TerminalSixel.build(logs, 1, 6);
        assertNotNull(sixel);
        assertFalse(sixel.resize(65536, 65536));
        assertFalse(sixel.resize(-1, 6));
        assertFalse(sixel.readData('~', -1));
        assertFalse(sixel.readData('~', 0));
        assertFalse(sixel.readData('~', Integer.MAX_VALUE));
        assertFalse(sixel.readData('!', 1));
        assertEquals(1, sixel.getBitmap().getWidth());
        int color = sixel.getColor();
        sixel.setRGBColor(1, Integer.MAX_VALUE, -1, 100);
        assertEquals(color, sixel.getColor());
        assertTrue(sixel.readData('?', TerminalSixel.SIXEL__MAX_REPEAT));
        int x = sixel.getCurX();
        assertFalse(sixel.readData('?', 1));
        assertEquals(x, sixel.getCurX());
        for (int i = 0; i < TerminalBitmap.MAX_BITMAP_DIMENSION / 6; i++) {
            assertTrue(sixel.readData('-', 1));
        }
        assertFalse(sixel.readData('-', 1));
        assertTrue(sixel.getCurY() <= TerminalBitmap.MAX_BITMAP_DIMENSION);
    }

    @Test
    public void singleAndMultipartBase64PreserveUsefulProtocolForms() {
        byte[] bytes = png(colors());
        String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        Image single = new Image(logs, false);
        StringBuilder args = new StringBuilder("inline=1;name=;width=50%;height=auto;preserveAspectRatio=0:").append(encoded);
        int payload = single.readArguments(emulator(), args, 0);
        assertTrue(payload > 0);
        assertEquals(320, single.getWidth());
        assertEquals(-1, single.getHeight());
        assertTrue(single.isInline());
        assertFalse(single.shouldPreserveAspectRatio());
        assertTrue(single.readImage(args, payload));
        assertEquals(encoded, single.getEncodedImage());
        assertTrue(single.decodeImage());
        assertArrayEquals(bytes, single.getDecodedImage());
        assertEquals(0, single.encodedLength());

        Image wrapped = new Image(logs, false);
        StringBuilder wrappedData = new StringBuilder("inline=1:\r\n").append(encoded).append('\n');
        int wrappedStart = wrapped.readArguments(emulator(), wrappedData, 0);
        assertTrue(wrapped.readImage(wrappedData, wrappedStart));
        assertTrue(wrapped.decodeImage());
        assertArrayEquals(bytes, wrapped.getDecodedImage());
        Image unpadded = new Image(logs, false);
        StringBuilder unpaddedData = new StringBuilder("inline=1:").append(encoded.replace("=", ""));
        int unpaddedStart = unpadded.readArguments(emulator(), unpaddedData, 0);
        assertTrue(unpadded.readImage(unpaddedData, unpaddedStart));
        assertTrue(unpadded.decodeImage());
        assertArrayEquals(bytes, unpadded.getDecodedImage());

        Image multi = new Image(logs, true);
        StringBuilder header = new StringBuilder("inline=1;width=2px;height=2px");
        assertEquals(header.length(), multi.readArguments(emulator(), header, 0));
        assertTrue(multi.readImage(new StringBuilder(), 0));
        assertTrue(multi.readImage(new StringBuilder(encoded.substring(0, 3)), 0));
        assertTrue(multi.readImage(new StringBuilder(encoded.substring(3)), 0));
        assertTrue(multi.setMultiPartImageRead());
        assertTrue(multi.decodeImage());
        assertArrayEquals(bytes, multi.getDecodedImage());
        assertEquals(ITermImage.ImageState.IMAGE_DECODED, multi.getCurrentState());
        assertEquals(ITermImage.ImageState.IMAGE_READ, multi.getPreviousState());
        assertEquals(0, multi.encodedLength());
    }

    private static boolean canAppendEncoded(int current, int additional) {
        return ReflectionHelpers.callStaticMethod(ITermImage.class, "canAppendEncodedImage",
            ClassParameter.from(int.class, current), ClassParameter.from(int.class, additional));
    }

    @Test
    public void multipartAggregateArithmeticAndFailureReleaseStaging() {
        // Verify the real upstream-limit boundary without allocating a 100/150 MiB fixture.
        int maximum = ITermImage.MAX_ENCODED_IMAGE_SIZE;
        assertEquals(TerminalBitmap.MAX_BITMAP_SIZE, maximum);
        assertTrue(canAppendEncoded(0, maximum));
        assertTrue(canAppendEncoded(maximum - 1, 1));
        assertTrue(canAppendEncoded(maximum, 0));
        assertFalse(canAppendEncoded(maximum, 1));
        assertFalse(canAppendEncoded(maximum - 1, 2));
        assertFalse(canAppendEncoded(1, Integer.MAX_VALUE));
        assertFalse(canAppendEncoded(-1, 1));
        assertFalse(canAppendEncoded(1, -1));

        Image multi = new Image(logs, true);
        assertTrue(multi.readArguments(emulator(), new StringBuilder("inline=1"), 0) >= 0);
        assertTrue(multi.readImage(new StringBuilder("AQI="), 0));
        assertEquals(4, multi.encodedLength());
        assertFalse(multi.readImage(new StringBuilder("_"), 0));
        assertEquals(ITermImage.ImageState.FAILED, multi.getCurrentState());
        assertEquals(ITermImage.ImageState.IMAGE_READING, multi.getPreviousState());
        assertEquals(0, multi.encodedLength());
        assertEquals(0, multi.encodedCapacity());
        assertNull(multi.getDecodedImage());
        assertFalse(multi.setMultiPartImageRead());
        assertFalse(multi.decodeImage());
    }

    @Test
    public void malformedArgumentsAndBase64NeverLeakPayloadsToLogs() {
        String secret = "PRIVATE_IMAGE_PAYLOAD_MUST_NOT_BE_LOGGED";
        Image badHeader = new Image(logs, false);
        assertEquals(-1, badHeader.readArguments(emulator(), new StringBuilder("inline=1;broken:").append(secret), 0));
        Image hugeWidth = new Image(logs, false);
        assertEquals(-1, hugeWidth.readArguments(emulator(), new StringBuilder("width=999999999999999999999999px:").append(secret), 0));
        Image negativeWidth = new Image(logs, false);
        assertEquals(-1, negativeWidth.readArguments(emulator(), new StringBuilder("width=-10px:").append(secret), 0));
        Image badCharacter = new Image(logs, false);
        StringBuilder data = new StringBuilder("inline=1:").append(secret);
        int start = badCharacter.readArguments(emulator(), data, 0);
        assertTrue(start >= 0);
        assertFalse(badCharacter.readImage(data, start));
        Image badPadding = new Image(logs, false);
        StringBuilder padding = new StringBuilder("inline=1:A===");
        int offset = badPadding.readArguments(emulator(), padding, 0);
        assertTrue(badPadding.readImage(padding, offset));
        assertFalse(badPadding.decodeImage());
        assertFalse(logs.messages.toString().contains(secret));
        assertFalse(logs.messages.toString().contains("A==="));
        assertNull(badPadding.getDecodedImage());
        assertEquals(0, badPadding.encodedLength());
    }

    @Test
    public void argumentRangesHandleLongKeysAndLeadingZerosWithoutPayloadCopies() {
        char[] chunk = new char[4096];
        Arrays.fill(chunk, 'X');
        StringBuilder unknown = new StringBuilder();
        for (int i = 0; i < 256; i++) unknown.append(chunk);
        unknown.append("=v;INLINE=1:AQI=");
        Image ignored = new Image(logs, false);
        int start = ignored.readArguments(emulator(), unknown, 0);
        assertTrue(start > 1024 * 1024);
        assertTrue(ignored.isInline());
        assertTrue(ignored.readImage(unknown, start));
        assertTrue(ignored.decodeImage());
        assertArrayEquals(new byte[] {1, 2}, ignored.getDecodedImage());

        Arrays.fill(chunk, '0');
        StringBuilder zeroWidth = new StringBuilder("WiDtH=");
        for (int i = 0; i < 256; i++) zeroWidth.append(chunk);
        zeroWidth.append("2px;HeIgHt=AuTo:AQI=");
        Image dimensions = new Image(logs, false);
        assertTrue(dimensions.readArguments(emulator(), zeroWidth, 0) > 1024 * 1024);
        assertEquals(2, dimensions.getWidth());
        assertEquals(-1, dimensions.getHeight());
        Arrays.fill(chunk, '9');
        StringBuilder enormous = new StringBuilder("width=");
        for (int i = 0; i < 256; i++) enormous.append(chunk);
        enormous.append("px:AQI=");
        assertEquals(-1, new Image(logs, false).readArguments(emulator(), enormous, 0));
    }

    @Test(timeout = 10000)
    public void repeatedShortArgumentsAreScannedLinearly() {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < 256 * 1024; i++) args.append("x=0;");
        args.append("inline=1:AQI=");
        Image image = new Image(logs, false);
        int separator = image.readArguments(emulator(), args, 0);
        assertEquals(args.length() - 5, separator);
        assertTrue(image.isInline());
        assertTrue(image.readImage(args, separator));
        assertTrue(image.decodeImage());
        assertArrayEquals(new byte[] {1, 2}, image.getDecodedImage());
    }

    @Test
    public void imagePlacementUsesMarginCallbackAndClipsWhenScrollingIsUnavailable() {
        MarginBuffer scrolling = new MarginBuffer(logs, true);
        Bitmap source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        TerminalBitmap placed = TerminalBitmap.build(scrolling, 0, source, 2, 3, 1, 1);
        assertNotNull(placed);
        assertEquals(1, placed.getBitmap().getWidth());
        assertEquals(2, placed.getBitmap().getHeight());
        assertEquals(1, scrolling.scrolls);
        assertEquals(1, placed.getBufferScrollLines()); // Informational; callback owns accounting.
        assertEquals(1, placed.getScrollLines());
        assertEquals(0, TextStyle.getTerminalBitmapY(scrolling.getStyleAt(2, 2)));
        assertEquals(1, TextStyle.getTerminalBitmapY(scrolling.getStyleAt(3, 2)));
        assertEquals("HHHHH", scrolling.getSelectedText(0, 0, 4, 0));
        assertEquals("FFFFF", scrolling.getSelectedText(0, 4, 4, 4));
        assertEquals("P", scrolling.getSelectedText(0, 3, 0, 3));
        assertEquals("PP", scrolling.getSelectedText(3, 3, 4, 3));

        MarginBuffer clipped = new MarginBuffer(logs, false);
        Bitmap tall = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888);
        tall.eraseColor(Color.BLUE);
        TerminalBitmap image = TerminalBitmap.build(clipped, 0, png(tall), 2, 3, 1, 1, -1, -1, true);
        assertNotNull(image);
        assertEquals(1, image.getBitmap().getHeight());
        assertEquals(Color.BLUE, image.getBitmap().getPixel(0, 0));
        assertArrayEquals(new int[] {1, 1}, image.getCursorDelta());
        assertEquals(0, clipped.scrolls);
        assertEquals(0, image.getBufferScrollLines());
        assertEquals("HHHHH", clipped.getSelectedText(0, 0, 4, 0));
        assertEquals("FFFFF", clipped.getSelectedText(0, 4, 4, 4));
    }

}
