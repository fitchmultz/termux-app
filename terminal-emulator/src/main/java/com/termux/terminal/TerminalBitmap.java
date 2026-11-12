package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;

/**
 * A terminal bitmap for images.
 */
public class TerminalBitmap {

    public static final String LOG_TAG = "TerminalBitmap";

    /**
     * Upstream's default Android draw-byte limit, NOT a heap allowance or RSS guarantee.
     * Android can permit larger device-specific values; no hidden API/getprop query is needed
     * to retain these defaults. Ordinary allocations may still fail with OutOfMemoryError.
     *
     * 100 MiB: Android 7 DisplayListCanvas.MAX_BITMAP_SIZE and Android 14 RecordingCanvas.
     * 150 MiB: Android 15 RecordingCanvas.getPanelFrameSize(), retained by Android 16.
     * - https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/view/DisplayListCanvas.java#40
     * - https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/graphics/java/android/graphics/RecordingCanvas.java#43
     * - https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/graphics/java/android/graphics/RecordingCanvas.java#43
     * - https://github.com/termux/termux-app/blob/3b270134a50ee50804fc722f6e17d3eac7e12407/terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java#L102-L112
     */
    public static final int MAX_BITMAP_SIZE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ?
        150 * 1024 * 1024 : 100 * 1024 * 1024;

    /**
     * Skia/Canvas's maximum software bitmap axis, independent of the draw-byte limit.
     * https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/graphics/java/android/graphics/Canvas.java#75
     */
    public static final int MAX_BITMAP_DIMENSION = 32766;

    /**
     * Bitmap IDs and cell offsets occupy 16 bits each in TextStyle's packed layout.
     * https://github.com/termux/termux-app/blob/3b270134a50ee50804fc722f6e17d3eac7e12407/terminal-emulator/src/main/java/com/termux/terminal/TextStyle.java#L94-L105
     */
    public static final int MAX_BITMAP_NUMBER = 0xffff;
    public static final int MAX_BITMAP_CELLS = 0xffff;

    /**
     * Retain upstream's image-data byte maximum; pixel dimensions are checked separately.
     * https://github.com/termux/termux-app/commit/3b270134a50ee50804fc722f6e17d3eac7e12407
     * (commit description: iTerm image data cannot exceed TerminalBitmap.MAX_BITMAP_SIZE).
     */
    public static final int MAX_IMAGE_DATA_SIZE = MAX_BITMAP_SIZE;


    protected final TerminalSessionClient mClient;

    protected int mBitmapNum;
    protected Bitmap mBitmap;

    protected int mCellWidth;
    protected int mCellHeight;

    protected int mScrollLines;
    protected int mBufferScrollLines;

    protected int[] mCursorDelta;


    protected TerminalBitmap(TerminalSessionClient client, int bitmapNum, Bitmap bitmap,
                             int cellWidth, int cellHeight,
                             int scrollLines, int[] cursorDelta) {
        mClient = client;

        mBitmapNum = bitmapNum;
        mBitmap = bitmap;

        mCellWidth = cellWidth;
        mCellHeight = cellHeight;

        mScrollLines = scrollLines;
        mCursorDelta = cursorDelta;
    }


    /** Checks dimensions before any allocation, including multiplication in long arithmetic. */
    public static boolean isBitmapSizeValid(long width, long height) {
        return width > 0 && height > 0 &&
            width <= MAX_BITMAP_DIMENSION && height <= MAX_BITMAP_DIMENSION &&
            width * height * 4L <= MAX_BITMAP_SIZE;
    }

    private static boolean isBitmapValid(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() &&
            isBitmapSizeValid(bitmap.getWidth(), bitmap.getHeight()) &&
            bitmap.getByteCount() <= MAX_BITMAP_SIZE;
    }

    /** Check drawable geometry/byte math before calling Android's ordinary allocator. */
    static Bitmap allocateBitmap(int width, int height) {
        if (!isBitmapSizeValid(width, height)) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return isBitmapValid(bitmap) ? bitmap : null;
    }

    private static void checkPlacement(TerminalBuffer terminalBuffer, int bitmapNum,
                                       int x, int y, int cellWidth, int cellHeight) {
        if (terminalBuffer == null || bitmapNum < 0 || bitmapNum > MAX_BITMAP_NUMBER ||
            terminalBuffer.getImageRightMargin() < 1 || terminalBuffer.getImageRightMargin() > terminalBuffer.mColumns ||
            terminalBuffer.getImageBottomMargin() < 1 || terminalBuffer.getImageBottomMargin() > terminalBuffer.mScreenRows ||
            x < 0 || x >= terminalBuffer.getImageRightMargin() || y < 0 || y >= terminalBuffer.getImageBottomMargin() ||
            cellWidth <= 0 || cellHeight <= 0) {
            throw new IllegalArgumentException("Invalid terminal bitmap placement");
        }
    }

    private static long roundUpToCell(long size, int cellSize) {
        return ((size + cellSize - 1L) / cellSize) * cellSize;
    }

    private static void logBuildFailure(TerminalBuffer buffer, int bitmapNum, String source) {
        Logger.logError(buffer != null ? buffer.getClient() : null, LOG_TAG,
            "Create terminal bitmap " + bitmapNum + " from " + source + " failed");
    }


    /** Build a {@link TerminalBitmap} from a {@link TerminalSixel}. */
    public static TerminalBitmap build(TerminalBuffer terminalBuffer, int bitmapNum, TerminalSixel terminalSixel,
                                       int x, int y, int cellWidth, int cellHeight) {
        try {
            checkPlacement(terminalBuffer, bitmapNum, x, y, cellWidth, cellHeight);
            if (terminalSixel == null) throw new IllegalArgumentException("Unset sixel");
            int height = terminalSixel.getHeight();
            if (!terminalBuffer.canScrollImage()) {
                height = (int) Math.min(height, (long) (terminalBuffer.getImageBottomMargin() - y) * cellHeight);
            }
            Bitmap bitmap = resizeBitmapConstrained(LOG_TAG, "sixel", terminalBuffer.getClient(),
                terminalSixel.getBitmap(), terminalSixel.getWidth(), height,
                cellWidth, cellHeight, terminalBuffer.getImageRightMargin() - x);
            if (bitmap == null) {
                logBuildFailure(terminalBuffer, bitmapNum, "terminal sixel");
                return null;
            }

            return buildOrThrow(terminalBuffer, bitmapNum, bitmap, x, y, cellWidth, cellHeight);
        } catch (RuntimeException | OutOfMemoryError e) {
            logBuildFailure(terminalBuffer, bitmapNum, "terminal sixel");
            return null;
        }
    }


    /** Build a {@link TerminalBitmap} from an image byte array. */
    public static TerminalBitmap build(TerminalBuffer terminalBuffer, int bitmapNum, byte[] image,
                                       int x, int y, int cellWidth, int cellHeight,
                                       int width, int height, boolean shouldPreserveAspectRatio) {
        try {
            checkPlacement(terminalBuffer, bitmapNum, x, y, cellWidth, cellHeight);
            if (image == null || image.length < 2 || image.length > MAX_IMAGE_DATA_SIZE ||
                width < -1 || height < -1 || width == 0 || height == 0 ||
                width > MAX_BITMAP_DIMENSION || height > MAX_BITMAP_DIMENSION) {
                throw new IllegalArgumentException("Invalid image data or requested dimensions");
            }

            // Always inspect the header first, even when neither size was requested. A small
            // compressed image is not evidence that its decoded bitmap will fit in memory.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
            int imageWidth = bounds.outWidth;
            int imageHeight = bounds.outHeight;
            ChafaTiffDecoder chafaTiff = null;
            if (imageWidth <= 0 || imageHeight <= 0) {
                // Native first, including TIFF-container RAW/DNG. Only claim Chafa's complete,
                // validated uncompressed layout when Android could not read native bounds.
                chafaTiff = ChafaTiffDecoder.parse(image);
                if (chafaTiff == null) throw new IllegalArgumentException("Invalid image header");
                imageWidth = chafaTiff.width;
                imageHeight = chafaTiff.height;
            }

            long newWidth = width > 0 ? width : imageWidth;
            long newHeight = height > 0 ? height : imageHeight;
            if (shouldPreserveAspectRatio && (width > 0 || height > 0)) {
                double factor = Math.min(width > 0 ? (double) width / imageWidth : Double.POSITIVE_INFINITY,
                    height > 0 ? (double) height / imageHeight : Double.POSITIVE_INFINITY);
                newWidth = Math.max(1L, (long) (factor * imageWidth));
                newHeight = Math.max(1L, (long) (factor * imageHeight));
            }

            // Preflight both the requested size and the padded terminal size, before decoding.
            // Large sources remain usable with an explicitly requested, bounded downsample.
            if (!isBitmapSizeValid(newWidth, newHeight)) {
                throw new IllegalArgumentException("Requested image dimensions exceed draw limits");
            }
            long maxWidth = (long) (terminalBuffer.getImageRightMargin() - x) * cellWidth;
            long visibleWidth = Math.min(newWidth, maxWidth);
            long finalWidth = Math.min(roundUpToCell(visibleWidth, cellWidth), maxWidth);
            long finalHeight = roundUpToCell(newHeight, cellHeight);
            if (!terminalBuffer.canScrollImage()) {
                finalHeight = Math.min(finalHeight, (long) (terminalBuffer.getImageBottomMargin() - y) * cellHeight);
            }
            if (!isBitmapSizeValid(finalWidth, finalHeight) ||
                finalWidth / cellWidth > MAX_BITMAP_CELLS || finalHeight / cellHeight > MAX_BITMAP_CELLS) {
                throw new IllegalArgumentException("Padded image dimensions exceed draw limits");
            }

            // Use the largest power-of-two sample which does not undershoot the requested
            // resolution. Long division avoids the old doubling/multiplication overflow loop.
            int sample = 1;
            while (sample <= Integer.MAX_VALUE / 2 &&
                imageWidth / (2L * sample) >= newWidth && imageHeight / (2L * sample) >= newHeight) {
                sample *= 2;
            }
            long decodedWidth = ((long) imageWidth + sample - 1) / sample;
            long decodedHeight = ((long) imageHeight + sample - 1) / sample;
            // A source just above a sample boundary may otherwise need four times the
            // target memory. Prefer a slightly lower-resolution decode to rejecting a
            // useful bounded request, then draw it at the requested output resolution.
            while (!isBitmapSizeValid(decodedWidth, decodedHeight) && sample <= Integer.MAX_VALUE / 2) {
                sample *= 2;
                decodedWidth = ((long) imageWidth + sample - 1) / sample;
                decodedHeight = ((long) imageHeight + sample - 1) / sample;
            }
            if (!isBitmapSizeValid(decodedWidth, decodedHeight)) {
                throw new IllegalArgumentException("Decoded image dimensions exceed draw limits");
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap decoded = BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (decoded == null) {
                if (chafaTiff == null) chafaTiff = ChafaTiffDecoder.parse(image);
                if (chafaTiff != null && chafaTiff.width == imageWidth && chafaTiff.height == imageHeight) {
                    decoded = chafaTiff.decode(sample);
                }
            }
            // Verify actual geometry/byte count too: the preferred config and header are
            // not promises about a decoder's result. Allocation OOM is handled by the caller.
            if (!isBitmapValid(decoded)) throw new IllegalArgumentException("Invalid decoded bitmap");

            Bitmap bitmap;
            if (decoded.getWidth() == finalWidth && decoded.getHeight() == finalHeight &&
                newWidth == finalWidth && newHeight == finalHeight) {
                bitmap = decoded;
            } else {
                // Draw the visible left portion directly into the padded destination. This
                // needs no intermediate cropped/scaled bitmap or full-size int[] pixel copy.
                bitmap = allocateBitmap((int) finalWidth, (int) finalHeight);
                if (bitmap == null) throw new IllegalArgumentException("Invalid resized image dimensions");
                Canvas canvas = new Canvas(bitmap);
                canvas.drawBitmap(decoded, null, new RectF(0, 0, (int) newWidth, (int) newHeight),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            }

            TerminalBitmap terminalBitmap = buildOrThrow(terminalBuffer, bitmapNum, bitmap,
                x, y, cellWidth, cellHeight);
            terminalBitmap.setCursorDelta(new int[] {
                terminalBitmap.getScrollLines(),
                (int) (((long) terminalBitmap.getBitmap().getWidth() + cellWidth - 1) / cellWidth)});
            return terminalBitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            // Do not log decoder exceptions: a decoder may include untrusted input in them.
            logBuildFailure(terminalBuffer, bitmapNum, "image byte array");
            return null;
        }
    }


    /** Decode a direct Kitty source once, before it can enter the reusable image cache. */
    static Bitmap decodeKitty(KittyImage command, byte[] image) {
        if (image == null || image.length > MAX_IMAGE_DATA_SIZE) return null;
        if (command.format == 100) {
            // f=100 means PNG, not any format accepted by BitmapFactory (nor Chafa TIFF).
            byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            if (image.length < signature.length) return null;
            for (int i = 0; i < signature.length; i++) if (image[i] != signature[i]) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
            if (!isBitmapSizeValid(bounds.outWidth, bounds.outHeight)) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeByteArray(image, 0, image.length, options);
            return isBitmapValid(decoded) && decoded.getWidth() == bounds.outWidth &&
                decoded.getHeight() == bounds.outHeight ? decoded : null;
        }
        int width = command.pixelWidth, height = command.pixelHeight, channels = command.format / 8;
        if (!isBitmapSizeValid(width, height) || (long) width * height * channels != image.length) return null;
        Bitmap decoded = allocateBitmap(width, height);
        if (decoded == null) return null;
        int[] row = new int[width];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = image[offset++] & 255, green = image[offset++] & 255, blue = image[offset++] & 255;
                int alpha = channels == 4 ? image[offset++] & 255 : 255;
                row[x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            // setPixels takes straight ARGB; Android handles premultiplication, including alpha0.
            decoded.setPixels(row, 0, width, 0, y, width, 1);
        }
        return decoded;
    }

    /**
     * Prepare crop/scale/padding entirely before publication. Dimensions come from decoded pixels,
     * not client s/v declarations. All requested and padded geometry is checked BEFORE allocation;
     * no createScaledBitmap/cropped temporary can bypass the common Android draw limits.
     */
    static Bitmap prepareKitty(TerminalBuffer buffer, Bitmap source, KittyImage command,
                               int x, int y, int cellWidth, int cellHeight) {
        if (!isBitmapValid(source) || cellWidth <= 0 || cellHeight <= 0) return null;
        int left = command.sourceX, top = command.sourceY;
        int right = (int) Math.min(source.getWidth(), (long) left +
            (command.sourceWidth > 0 ? command.sourceWidth : source.getWidth()));
        int bottom = (int) Math.min(source.getHeight(), (long) top +
            (command.sourceHeight > 0 ? command.sourceHeight : source.getHeight()));
        if (left >= right || top >= bottom) return null;
        int cropWidth = right - left, cropHeight = bottom - top;
        long width = command.columns > 0 ? (long) command.columns * cellWidth : cropWidth;
        long height = command.rows > 0 ? (long) command.rows * cellHeight : cropHeight;
        if (command.columns > 0 && command.rows == 0)
            height = Math.max(1, (width * cropHeight + cropWidth - 1) / cropWidth);
        else if (command.rows > 0 && command.columns == 0)
            width = Math.max(1, (height * cropWidth + cropHeight - 1) / cropHeight);
        if (!isBitmapSizeValid(width, height)) return null;
        long maxWidth = (long) (buffer.getImageRightMargin() - x) * cellWidth;
        long paddedWidth = Math.min(maxWidth, roundUpToCell(width, cellWidth));
        long paddedHeight = roundUpToCell(height, cellHeight);
        // Kitty scrolls only as a consequence of cursor movement (screen.c handle_graphics_command).
        // C=1 stays anchored and clips at the bottom; it must not scroll even inside valid margins.
        if (command.noMove || !buffer.canScrollImage())
            paddedHeight = Math.min(paddedHeight, (long) (buffer.getImageBottomMargin() - y) * cellHeight);
        if (!isBitmapSizeValid(paddedWidth, paddedHeight) ||
            paddedWidth / cellWidth > MAX_BITMAP_CELLS || paddedHeight / cellHeight > MAX_BITMAP_CELLS) return null;
        Bitmap result = allocateBitmap((int) paddedWidth, (int) paddedHeight);
        if (result == null) return null;
        // The protocol reserves c/r but preserves source aspect ratio within that rectangle.
        double scale = Math.min((double) width / cropWidth, (double) height / cropHeight);
        float drawWidth = (float) (cropWidth * scale), drawHeight = (float) (cropHeight * scale);
        float dx = (width - drawWidth) / 2, dy = (height - drawHeight) / 2;
        // A filtered source Rect still samples neighboring pixels outside the selected region.
        // Isolate the crop so filtering clamps to its edges, not to the full cached image.
        // Both allocations follow geometry admission; this crop cannot exceed the valid source.
        Bitmap cropped = left == 0 && top == 0 && right == source.getWidth() && bottom == source.getHeight()
            ? source : Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
        new Canvas(result).drawBitmap(cropped, null,
            new RectF(dx, dy, dx + drawWidth, dy + drawHeight), new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    /** Build a {@link TerminalBitmap} from a {@link Bitmap}. */
    public static TerminalBitmap build(TerminalBuffer terminalBuffer, int bitmapNum, Bitmap bitmap,
                                       int x, int y, int cellWidth, int cellHeight) {
        try {
            return buildOrThrow(terminalBuffer, bitmapNum, bitmap, x, y, cellWidth, cellHeight);
        } catch (RuntimeException | OutOfMemoryError e) {
            logBuildFailure(terminalBuffer, bitmapNum, "bitmap");
            return null;
        }
    }

    /** Build a bitmap, validating and allocating before changing any terminal cells. */
    public static TerminalBitmap buildOrThrow(TerminalBuffer terminalBuffer, int bitmapNum, Bitmap bitmap,
                                              int x, int y, int cellWidth, int cellHeight) {
        checkPlacement(terminalBuffer, bitmapNum, x, y, cellWidth, cellHeight);
        if (!isBitmapValid(bitmap)) {
            throw new IllegalArgumentException("Invalid terminal bitmap");
        }

        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        int width = (int) Math.min(terminalBuffer.getImageRightMargin() - x, ((long) bitmapWidth + cellWidth - 1) / cellWidth);
        if (!terminalBuffer.canScrollImage()) {
            bitmapHeight = (int) Math.min(bitmapHeight, (long) (terminalBuffer.getImageBottomMargin() - y) * cellHeight);
        }
        int height = (int) (((long) bitmapHeight + cellHeight - 1) / cellHeight);
        if (width < 1 || height < 1 || width > MAX_BITMAP_CELLS || height > MAX_BITMAP_CELLS) {
            throw new IllegalArgumentException("Bitmap cell coordinates exceed TextStyle encoding");
        }

        int cropWidth = (int) Math.min(bitmapWidth, (long) width * cellWidth);
        if (cropWidth < bitmapWidth || bitmapHeight < bitmap.getHeight()) {
            bitmap = resizeBitmap(LOG_TAG, "image crop", terminalBuffer.getClient(), bitmap, cropWidth, bitmapHeight);
            if (bitmap == null) throw new IllegalArgumentException("Invalid cropped image dimensions");
        }

        // No bitmap allocations after the screen has begun to change. Never recycle or mutate
        // this bitmap after publication: presentation snapshots may retain the same object.
        TerminalBitmap result = new TerminalBitmap(terminalBuffer.getClient(), bitmapNum, bitmap,
            cellWidth, cellHeight, 0, null);
        int s = 0;
        int row = y;
        for (int i = 0; i < height; i++, row++) {
            if (row == terminalBuffer.getImageBottomMargin()) {
                if (!terminalBuffer.scrollForImage()) {
                    throw new IllegalStateException("Image placement cannot scroll beyond its region");
                }
                row--;
                s++;
            }
            for (int j = 0; j < width; j++) {
                terminalBuffer.setChar(x + j, row, '+', TextStyle.encodeTerminalBitmap(bitmapNum, j, i));
            }
        }
        result.mScrollLines = height - s;
        result.mBufferScrollLines = s;
        return result;
    }


    public TerminalSessionClient getClient() {
        return mClient;
    }

    public int getBitmapNum() {
        return mBitmapNum;
    }

    /**
     * Borrows the placement's pixels, which may also be retained by completed screen snapshots.
     * Callers must not mutate or recycle the bitmap, including after live-buffer eviction.
     * A retained reference keeps the pixels alive; a caller needing writable pixels must copy them.
     */
    public Bitmap getBitmap() {
        return mBitmap;
    }

    public int getCellWidth() {
        return mCellWidth;
    }

    public int getCellHeight() {
        return mCellHeight;
    }

    public int getScrollLines() {
        return mScrollLines;
    }

    /**
     * Placement scroll operations, separate from subsequent cursor moves. Informational when a
     * margin-aware callback already accounts for scrolling; callers must not count them twice.
     */
    public int getBufferScrollLines() {
        return mBufferScrollLines;
    }

    public int[] getCursorDelta() {
        return mCursorDelta;
    }

    public void setCursorDelta(int[] cursorDelta) {
        mCursorDelta = cursorDelta;
    }


    public static Bitmap resizeBitmap(String logTag, String label, TerminalSessionClient client, Bitmap bitmap,
                                      int bitmapWidth, int bitmapHeight) {
        try {
            if (!isBitmapValid(bitmap) || !isBitmapSizeValid(bitmapWidth, bitmapHeight)) {
                Logger.logError(client, logTag, "Invalid " + label + " bitmap resize dimensions");
                return null;
            }
            if (bitmapWidth == bitmap.getWidth() && bitmapHeight == bitmap.getHeight()) return bitmap;

            Bitmap newBitmap = allocateBitmap(bitmapWidth, bitmapHeight);
            if (newBitmap == null) return null;
            // Canvas clips to the destination and leaves new pixels transparent. The old code
            // allocated one int per allocation BYTE (four times as many pixels as required).
            new Canvas(newBitmap).drawBitmap(bitmap, null,
                new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), null);
            return newBitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            Logger.logError(client, logTag, "Resize " + label + " bitmap failed");
            return null;
        }
    }

    public static Bitmap resizeBitmapConstrained(String logTag, String label, TerminalSessionClient client, Bitmap bitmap,
                                                 int bitmapWidth, int bitmapHeight,
                                                 int cellWidth, int cellHeight, int columns) {
        if (!isBitmapValid(bitmap) || !isBitmapSizeValid(bitmapWidth, bitmapHeight) ||
            cellWidth <= 0 || cellHeight <= 0 || columns <= 0) {
            return null;
        }

        // Width and height must be multiples of the cell size and fit the remaining columns.
        long maxWidth = (long) cellWidth * columns;
        long newWidth = Math.min(maxWidth, roundUpToCell(bitmapWidth, cellWidth));
        long newHeight = roundUpToCell(bitmapHeight, cellHeight);
        if (!isBitmapSizeValid(newWidth, newHeight) ||
            newWidth / cellWidth > MAX_BITMAP_CELLS || newHeight / cellHeight > MAX_BITMAP_CELLS) {
            return null;
        }
        // A failed constraint is not a minor display glitch: never fall back to an oversized
        // original bitmap, whose coordinates or allocation may be unsafe for the renderer.
        return resizeBitmap(logTag, label, client, bitmap, (int) newWidth, (int) newHeight);
    }

}
