package com.termux.terminal;

import android.graphics.Bitmap;

/**
 * A terminal sixel received via `DCS q s..s ST` or `DCS P1; P2; P3; q s..s ST`.
 *
 * **See Also:**
 * - `TerminalEmulator.ESC_DCS__SIXEL`
 * - https://vt100.net/docs/vt3xx-gp/chapter14.html
 * - https://en.wikipedia.org/wiki/Sixel
 * - https://www.digiater.nl/openvms/decus/vax90b1/krypton-nasa/all-about-sixels.text
 */
public class TerminalSixel {

    public static final String LOG_TAG = "TerminalSixel";

    public static final int[] SIXEL__INITIAL_COLOR_MAP = {
        0xFF000000, 0xFF3333CC, 0xFFCC2323, 0xFF33CC33, 0xFFCC33CC, 0xFF33CCCC, 0xFFCCCC33, 0xFF777777,
        0xFF444444, 0xFF565699, 0xFF994444, 0xFF569956, 0xFF995699, 0xFF569999, 0xFF999956, 0xFFCCCCCC
    };

    /** A sixel is a group of six pixels in a vertical column. */
    public static final int SIXEL__LINE_LEN = 6;

    /** Upstream's extra capacity when receiving data, subject to drawable dimensions/bytes. */
    public static final int SIXEL__BITMAP_RESIZE_EXTRA_DIMENSION = 100;

    /** A repeat cannot exceed the largest drawable bitmap axis, regardless of compression. */
    public static final int SIXEL__MAX_REPEAT = TerminalBitmap.MAX_BITMAP_DIMENSION;


    protected final TerminalSessionClient mClient;

    protected Bitmap mBitmap;

    protected int mWidth;
    protected int mHeight;

    protected int mCurX;
    protected int mCurY;

    protected final int[] mColorMap;
    protected int mColor;


    protected TerminalSixel(TerminalSessionClient client, Bitmap bitmap) {
        mClient = client;
        mBitmap = bitmap;

        mWidth = 0;
        mHeight = 0;
        mCurX = 0;
        mCurY = 0;

        mColorMap = new int[256];
        System.arraycopy(SIXEL__INITIAL_COLOR_MAP, 0, mColorMap, 0, 16);
        mColor = mColorMap[0];
    }


    public static TerminalSixel build(TerminalSessionClient client, int bitmapWidth, int bitmapHeight) {
        if (!TerminalBitmap.isBitmapSizeValid(bitmapWidth, bitmapHeight)) {
            Logger.logError(client, LOG_TAG, "Invalid initial sixel bitmap dimensions");
            return null;
        }
        try {
            Bitmap bitmap = TerminalBitmap.allocateBitmap(bitmapWidth, bitmapHeight);
            if (bitmap == null) {
                Logger.logError(client, LOG_TAG, "Invalid initial sixel bitmap dimensions");
                return null;
            }
            bitmap.eraseColor(0);
            return new TerminalSixel(client, bitmap);
        } catch (RuntimeException | OutOfMemoryError e) {
            Logger.logError(client, LOG_TAG, "Create sixel bitmap failed");
            return null;
        }
    }


    public TerminalSessionClient getClient() {
        return mClient;
    }

    /**
     * Borrows this decoder's working bitmap without transferring ownership.
     * The decoder may mutate or replace it while parsing; callers must not mutate or recycle it.
     * Reading belongs on the parser's owning thread. This is not a completed-screen snapshot.
     */
    public Bitmap getBitmap() {
        return mBitmap;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getCurX() {
        return mCurX;
    }

    public int getCurY() {
        return mCurY;
    }

    public int[] getColorMap() {
        return mColorMap;
    }

    public int getColor() {
        return mColor;
    }


    public boolean readData(int codePoint, int repeat) {
        if (mBitmap == null || mBitmap.isRecycled()) return false;
        if (repeat < 1 || repeat > SIXEL__MAX_REPEAT) {
            Logger.logError(mClient, LOG_TAG, "Invalid sixel repeat count");
            return false;
        }

        // Graphics Carriage Return: overprint the same sixel line from the left margin.
        if (codePoint == '$') {
            mCurX = 0;
            return true;
        }

        // Graphics New Line: bound even a stream of newlines which paints no pixels.
        if (codePoint == '-') {
            long newY = (long) mCurY + SIXEL__LINE_LEN;
            if (newY > TerminalBitmap.MAX_BITMAP_DIMENSION) return false;
            mCurX = 0;
            mCurY = (int) newY;
            return true;
        }

        // Validate BEFORE growing the bitmap. Only '?' through '~' encode sixel pixels.
        if (codePoint < '?' || codePoint > '~') return false;
        long requiredWidth = (long) mCurX + repeat;
        long requiredHeight = (long) mCurY + SIXEL__LINE_LEN;
        if (!TerminalBitmap.isBitmapSizeValid(Math.max(mWidth, requiredWidth), Math.max(mHeight, requiredHeight))) {
            Logger.logError(mClient, LOG_TAG, "Sixel data exceeds drawable bitmap dimensions or byte count");
            return false;
        }

        if (requiredWidth > mBitmap.getWidth() || requiredHeight > mBitmap.getHeight()) {
            long newWidth = Math.max(mBitmap.getWidth(), requiredWidth > mBitmap.getWidth() ?
                Math.min(TerminalBitmap.MAX_BITMAP_DIMENSION, requiredWidth + SIXEL__BITMAP_RESIZE_EXTRA_DIMENSION) : requiredWidth);
            long newHeight = Math.max(mBitmap.getHeight(), requiredHeight > mBitmap.getHeight() ?
                Math.min(TerminalBitmap.MAX_BITMAP_DIMENSION, requiredHeight + SIXEL__BITMAP_RESIZE_EXTRA_DIMENSION) : requiredHeight);
            if (!TerminalBitmap.isBitmapSizeValid(newWidth, newHeight)) {
                // Capacity headroom must not make an otherwise valid image fail at the limit.
                // Discard only unused preallocation, never painted or declared raster pixels.
                newWidth = Math.max(mWidth, requiredWidth);
                newHeight = Math.max(mHeight, requiredHeight);
            }
            Bitmap resized = TerminalBitmap.resizeBitmap(LOG_TAG, "sixel", mClient, mBitmap,
                (int) newWidth, (int) newHeight);
            if (resized == null) return false;
            mBitmap = resized;
        }

        int bits = codePoint - '?';
        try {
            for (int column = 0; column < repeat; column++) {
                for (int bit = 0; bit < SIXEL__LINE_LEN; bit++) {
                    if ((bits & (1 << bit)) != 0) {
                        mBitmap.setPixel(mCurX + column, mCurY + bit, mColor);
                    }
                }
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            Logger.logError(mClient, LOG_TAG, "Draw sixel pixels failed");
            return false;
        }
        mCurX = (int) requiredWidth;
        mWidth = Math.max(mWidth, mCurX);
        mHeight = Math.max(mHeight, (int) requiredHeight);
        return true;
    }

    /** Reserve/declare raster extents without trusting input dimensions or overflowing int. */
    public boolean resize(int sixelWidth, int sixelHeight) {
        if (mBitmap == null || mBitmap.isRecycled() ||
            !TerminalBitmap.isBitmapSizeValid(sixelWidth, sixelHeight)) {
            return false;
        }

        int width = Math.max(mWidth, sixelWidth);
        int height = Math.max(mHeight, sixelHeight);
        if (!TerminalBitmap.isBitmapSizeValid(width, height)) return false;

        int bitmapWidth = Math.max(mBitmap.getWidth(), width);
        int bitmapHeight = Math.max(mBitmap.getHeight(), height);
        if (!TerminalBitmap.isBitmapSizeValid(bitmapWidth, bitmapHeight)) {
            bitmapWidth = width;
            bitmapHeight = height;
        }
        if (bitmapWidth != mBitmap.getWidth() || bitmapHeight != mBitmap.getHeight()) {
            Bitmap resized = TerminalBitmap.resizeBitmap(LOG_TAG, "sixel", mClient, mBitmap, bitmapWidth, bitmapHeight);
            if (resized == null) return false;
            mBitmap = resized;
        }
        mWidth = width;
        mHeight = height;
        return true;
    }

    public void setColor(int color) {
        if (color >= 0 && color < mColorMap.length) mColor = mColorMap[color];
    }

    public void setRGBColor(int color, int r, int g, int b) {
        if (color >= 0 && color < mColorMap.length &&
            r >= 0 && r <= 100 && g >= 0 && g <= 100 && b >= 0 && b <= 100) {
            // Validate before multiplying: negative or overflowing components must not alter
            // adjacent ARGB components if a caller passes malformed color parameters.
            mColor = 0xff000000 | ((r * 255 / 100) << 16) | ((g * 255 / 100) << 8) | (b * 255 / 100);
            mColorMap[color] = mColor;
        }
    }

}
