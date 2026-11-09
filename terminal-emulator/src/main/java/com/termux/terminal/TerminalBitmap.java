package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * A terminal bitmap for images.
 */
public class TerminalBitmap {

    public static final String LOG_TAG = "TerminalBitmap";

    /**
     * Budget for one ARGB_8888 bitmap, not for the complete terminal image cache.
     *
     * Android's RecordingCanvas limit (at least 100 MiB) is a draw limit, not an allocation
     * budget for an app without largeHeap. Use at most 16 MiB (enough for a 2560x1440 image),
     * and at most one eighth of the normal process heap. Decoding/resizing can briefly need
     * both a source and a destination bitmap; encoded data and terminal snapshots also need
     * headroom. TerminalBuffer must separately bound its aggregate retained image memory.
     */
    public static final int MAX_BITMAP_SIZE = (int) Math.min(16L * 1024 * 1024,
        TerminalImageBudget.MAX_BYTES / 2);

    /** Skia/Canvas's maximum bitmap axis, independent of the byte-size limit. */
    public static final int MAX_BITMAP_DIMENSION = 32766;

    /** Bitmap IDs and cell offsets occupy 16 bits each in TextStyle. */
    public static final int MAX_BITMAP_NUMBER = 0xffff;
    public static final int MAX_BITMAP_CELLS = 0xffff;

    /**
     * Bound compressed input separately from decoded pixels. Its base64 representation is
     * at most half MAX_BITMAP_SIZE characters (plus no unbounded multipart accumulation).
     * Even with UTF-16 storage, the builder, decode string and byte[] need heap headroom.
     */
    public static final int MAX_IMAGE_DATA_SIZE = (MAX_BITMAP_SIZE / 8) * 3;


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
            bitmap.getAllocationByteCount() <= MAX_BITMAP_SIZE &&
            TerminalImageBudget.GLOBAL.trySetCharge(bitmap, bitmap.getAllocationByteCount());
    }

    /** All helper pixel allocations reserve globally before calling Android's allocator. */
    static Bitmap allocateBitmap(int width, int height) {
        if (!isBitmapSizeValid(width, height)) return null;
        try (TerminalImageBudget.Reservation reservation =
                 TerminalImageBudget.GLOBAL.tryReserve((long) width * height * 4)) {
            if (reservation == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (!reservation.commitToBitmap(bitmap) || !isBitmapValid(bitmap)) return null;
            return bitmap;
        }
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

            if (!TerminalImageBudget.GLOBAL.trySetCharge(image, image.length)) {
                throw new IllegalArgumentException("Image data exceeds global memory budget");
            }

            // Always inspect the header first, even when neither size was requested. A small
            // compressed image is not evidence that its decoded bitmap will fit in memory.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
            int imageWidth = bounds.outWidth;
            int imageHeight = bounds.outHeight;
            if (imageWidth <= 0 || imageHeight <= 0 ||
                imageWidth > MAX_BITMAP_DIMENSION || imageHeight > MAX_BITMAP_DIMENSION) {
                throw new IllegalArgumentException("Invalid image header");
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
                throw new IllegalArgumentException("Requested image dimensions exceed bitmap budget");
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
                throw new IllegalArgumentException("Padded image dimensions exceed bitmap budget");
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
                throw new IllegalArgumentException("Decoded image dimensions exceed bitmap budget");
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap decoded;
            try (TerminalImageBudget.Reservation reservation =
                     TerminalImageBudget.GLOBAL.tryReserve(decodedWidth * decodedHeight * 4)) {
                if (reservation == null) throw new IllegalArgumentException("Image decode exceeds global memory budget");
                decoded = BitmapFactory.decodeByteArray(image, 0, image.length, options);
                // Record actual memory immediately, even if a decoder returned an unexpected
                // color config/size. The weak charge remains until that bitmap is collected.
                if (!reservation.commitToBitmap(decoded) || !isBitmapValid(decoded)) {
                    throw new IllegalArgumentException("Invalid decoded bitmap");
                }
            }

            Bitmap bitmap;
            if (decoded.getWidth() == finalWidth && decoded.getHeight() == finalHeight &&
                newWidth == finalWidth && newHeight == finalHeight) {
                bitmap = decoded;
            } else {
                // Draw the visible left portion directly into the padded destination. This
                // needs no intermediate cropped/scaled bitmap or full-size int[] pixel copy.
                bitmap = allocateBitmap((int) finalWidth, (int) finalHeight);
                if (bitmap == null) throw new IllegalArgumentException("Image resize exceeds global memory budget");
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
            if (bitmap == null) throw new IllegalArgumentException("Image crop exceeds global memory budget");
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
