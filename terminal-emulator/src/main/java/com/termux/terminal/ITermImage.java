package com.termux.terminal;

import android.util.Base64;

/**
 * An iTerm image received via `OSC 1337`.
 *
 * - https://iterm2.com/documentation-images.html
 */
public class ITermImage {

    public static final String LOG_TAG = "ITermImage";

    /** Includes every encoded character across ALL FilePart commands, including whitespace. */
    public static final int MAX_ENCODED_IMAGE_SIZE = (TerminalBitmap.MAX_IMAGE_DATA_SIZE / 3) * 4;


    /** The {@link Enum} that defines {@link ITermImage} state. */
    public enum ImageState {
        INIT("init", 0),
        ARGUMENTS_READ("arguments_read", 1),
        IMAGE_READING("image_reading", 2),
        IMAGE_READ("image_read", 3),
        IMAGE_DECODED("image_decoded", 4),
        FAILED("failed", 5);

        private final String name;
        private final int value;

        ImageState(final String name, final int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }
    }


    protected final TerminalSessionClient mClient;
    protected final boolean mIsMultipart;

    protected int mWidth = -1;
    protected int mHeight = -1;
    protected boolean mInline = false;
    protected boolean mPreserveAspectRatio = true;

    // Start empty. Every capacity increase is admitted before allocating a replacement.
    protected StringBuilder mEncodedImage = new StringBuilder(0);
    protected byte[] mDecodedImage;

    protected ImageState mCurrentState = ImageState.INIT;
    protected ImageState mPreviousState = ImageState.INIT;


    protected ITermImage(TerminalSessionClient client, boolean isMultiPart) {
        mClient = client;
        mIsMultipart = isMultiPart;
    }


    public TerminalSessionClient getClient() {
        return mClient;
    }

    public boolean isMultipart() {
        return mIsMultipart;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public boolean isInline() {
        return mInline;
    }

    public boolean shouldPreserveAspectRatio() {
        return mPreserveAspectRatio;
    }

    /** Returns an admitted copy, or null if the extra copy would exceed the global budget. */
    public synchronized String getEncodedImage() {
        if (mEncodedImage.length() == 0) return "";
        try (TerminalImageBudget.Reservation reservation =
                 TerminalImageBudget.GLOBAL.tryReserve(2L * mEncodedImage.length())) {
            if (reservation == null) return null;
            String encoded = mEncodedImage.toString();
            return reservation.commitTo(encoded, 2L * encoded.length()) ? encoded : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    public byte[] getDecodedImage() {
        return mDecodedImage;
    }

    public synchronized ImageState getCurrentState() {
        return mCurrentState;
    }

    public synchronized ImageState getPreviousState() {
        return mPreviousState;
    }


    protected synchronized boolean setState(ImageState newState) {
        if (newState.getValue() < mCurrentState.getValue() || mCurrentState == ImageState.IMAGE_DECODED) {
            Logger.logError(mClient, LOG_TAG,
                "Invalid image state transition from " + mCurrentState.getName() + " to " + newState.getName());
            return false;
        }
        if (mCurrentState != ImageState.FAILED) mPreviousState = mCurrentState;
        mCurrentState = newState;
        return true;
    }

    private void clearEncodedImage() {
        mEncodedImage.setLength(0);
        mEncodedImage.trimToSize();
        TerminalImageBudget.GLOBAL.release(mEncodedImage);
    }

    private boolean ensureEncodedCapacity(int required) {
        if (required <= mEncodedImage.capacity()) return true;
        int capacity = (int) Math.min(MAX_ENCODED_IMAGE_SIZE,
            Math.max(required, 2L * mEncodedImage.capacity() + 2));
        try (TerminalImageBudget.Reservation reservation =
                 TerminalImageBudget.GLOBAL.tryReserve(2L * capacity)) {
            if (reservation == null) return false;
            StringBuilder replacement = new StringBuilder(capacity);
            if (!reservation.commitTo(replacement, 2L * replacement.capacity())) {
                replacement.trimToSize();
                TerminalImageBudget.GLOBAL.release(replacement);
                return false;
            }
            // The old builder remains charged while both arrays are needed for this copy.
            replacement.append(mEncodedImage);
            StringBuilder previous = mEncodedImage;
            mEncodedImage = replacement;
            previous.setLength(0);
            previous.trimToSize();
            TerminalImageBudget.GLOBAL.release(previous);
            return true;
        }
    }

    protected synchronized boolean setStateFailed(String error) {
        // Callers supply fixed diagnostic text only. Never log OSC arguments, encoded data,
        // decoded bytes or decoder exception messages/stack traces containing image payloads.
        if (error != null) Logger.logError(mClient, LOG_TAG, error);
        if (!setState(ImageState.FAILED)) return false;
        clearEncodedImage();
        TerminalImageBudget.GLOBAL.release(mDecodedImage);
        mDecodedImage = null;
        return true;
    }

    protected synchronized boolean ensureState(ImageState expectedState) {
        return ensureState(expectedState, null);
    }

    protected synchronized boolean ensureState(ImageState expectedState, String functionName) {
        if (mCurrentState != expectedState) {
            Logger.logError(mClient, LOG_TAG,
                "Current image state is " + mCurrentState.getName() + ", expected " + expectedState.getName() +
                (functionName != null ? " in " + functionName : "") +
                " for " + (mIsMultipart ? "multipart" : "singlepart") + " image");
            return false;
        }
        return true;
    }

    public synchronized boolean isArgumentsRead() {
        return mCurrentState == ImageState.ARGUMENTS_READ;
    }

    public synchronized boolean isImageReading() {
        return mCurrentState == ImageState.IMAGE_READING;
    }

    public synchronized boolean isImageRead() {
        return mCurrentState == ImageState.IMAGE_READ;
    }

    public synchronized boolean isImageDecoded() {
        return mCurrentState == ImageState.IMAGE_DECODED;
    }


    private static boolean equalsAsciiIgnoreCase(CharSequence value, int start, int end, String expected) {
        if (start < 0 || end < start || end > value.length() || end - start != expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            char ch = value.charAt(start + i);
            if (ch >= 'A' && ch <= 'Z') ch += 'a' - 'A';
            if (ch != expected.charAt(i)) return false;
        }
        return true;
    }

    private static int parseDimension(CharSequence value, int start, int end, int cellSize, int cells) {
        if (equalsAsciiIgnoreCase(value, start, end, "auto")) return -1;
        double factor = cellSize;
        int numberEnd = end;
        if (end - start >= 2 && equalsAsciiIgnoreCase(value, end - 2, end, "px")) {
            factor = 1;
            numberEnd -= 2;
        } else if (end > start && value.charAt(end - 1) == '%') {
            factor = 0.01 * cellSize * cells;
            numberEnd--;
        }
        if (numberEnd <= start || factor <= 0) throw new IllegalArgumentException("Invalid image dimension");
        // Parse directly from the caller's admitted builder. Even a million leading zeros
        // or an unknown million-character key must not allocate an uncharged substring.
        long number = 0;
        for (int i = start; i < numberEnd; i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9' || number > (Long.MAX_VALUE - (digit - '0')) / 10) {
                throw new IllegalArgumentException("Invalid image dimension");
            }
            number = number * 10 + digit - '0';
        }
        double pixels = number * factor;
        if (number <= 0 || !Double.isFinite(pixels) || pixels > TerminalBitmap.MAX_BITMAP_DIMENSION) {
            throw new IllegalArgumentException("Image dimension exceeds limit");
        }
        return Math.max(1, (int) pixels);
    }

    public synchronized int readArguments(TerminalEmulator terminalEmulator, StringBuilder oscArgs, int index) {
        if (!ensureState(ImageState.INIT, "readArguments")) return -1;
        if (terminalEmulator == null || oscArgs == null || index < 0 || index > oscArgs.length()) {
            setStateFailed("Invalid image argument range");
            return -1;
        }

        try {
            while (index < oscArgs.length()) {
                char ch = oscArgs.charAt(index);
                if (ch == ':' && !mIsMultipart) break;
                if (ch == ' ') {
                    index++;
                    continue;
                }

                // Scan this field once. Searching for the final ':' again for every
                // short argument would make a large, repeated-argument header quadratic.
                int equals = -1;
                int end = index;
                while (end < oscArgs.length()) {
                    char fieldChar = oscArgs.charAt(end);
                    if (fieldChar == ';' || (!mIsMultipart && fieldChar == ':')) break;
                    if (fieldChar == '=' && equals < 0) equals = end;
                    end++;
                }
                boolean colon = end < oscArgs.length() && oscArgs.charAt(end) == ':';
                if (equals <= index || equals >= end || (!mIsMultipart && end == oscArgs.length())) {
                    setStateFailed("Invalid image argument syntax");
                    return -1;
                }

                // Compare short known keys in-place. Unknown keys/values (including names
                // and declared file sizes) never allocate copies or supply allocation hints.
                if (equalsAsciiIgnoreCase(oscArgs, index, equals, "inline")) {
                    mInline = end == equals + 2 && oscArgs.charAt(equals + 1) == '1';
                } else if (equalsAsciiIgnoreCase(oscArgs, index, equals, "preserveaspectratio")) {
                    mPreserveAspectRatio = !(end == equals + 2 && oscArgs.charAt(equals + 1) == '0');
                } else if (equalsAsciiIgnoreCase(oscArgs, index, equals, "width")) {
                    mWidth = parseDimension(oscArgs, equals + 1, end,
                        terminalEmulator.getCellWidthPixels(), terminalEmulator.getColumns());
                } else if (equalsAsciiIgnoreCase(oscArgs, index, equals, "height")) {
                    mHeight = parseDimension(oscArgs, equals + 1, end,
                        terminalEmulator.getCellHeightPixels(), terminalEmulator.getRows());
                }

                index = end;
                if (colon) break;
                if (index < oscArgs.length()) index++;
            }
            setState(ImageState.ARGUMENTS_READ);
            return index;
        } catch (RuntimeException | OutOfMemoryError e) {
            setStateFailed("Invalid image arguments");
            return -1;
        }
    }


    private boolean appendImage(StringBuilder oscArgs, int index) {
        if (oscArgs == null || index < 0 || index > oscArgs.length()) {
            setStateFailed("Invalid image payload range");
            return false;
        }
        int length = oscArgs.length() - index;
        if (length > MAX_ENCODED_IMAGE_SIZE - mEncodedImage.length()) {
            setStateFailed("Encoded image exceeds aggregate size limit");
            return false;
        }
        for (int i = index; i < oscArgs.length(); i++) {
            char ch = oscArgs.charAt(i);
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=' ||
                ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n')) {
                setStateFailed("Invalid base64 image character");
                return false;
            }
        }
        try {
            // No substring copy, no implicit builder growth, and both aggregate/per-app
            // admission checks precede collection of any additional payload.
            if (!ensureEncodedCapacity(mEncodedImage.length() + length)) {
                setStateFailed("Encoded image exceeds global memory budget");
                return false;
            }
            mEncodedImage.append(oscArgs, index, oscArgs.length());
            return true;
        } catch (RuntimeException | OutOfMemoryError e) {
            setStateFailed("Collecting encoded image failed");
            return false;
        }
    }

    public synchronized boolean readImage(StringBuilder oscArgs, int index) {
        if (!mIsMultipart) {
            if (!ensureState(ImageState.ARGUMENTS_READ, "readImage")) return false;
            if (oscArgs == null || index < 0 || index >= oscArgs.length() || oscArgs.charAt(index) != ':') {
                setStateFailed("Missing singlepart image separator");
                return false;
            }
            setState(ImageState.IMAGE_READING);
            if (!appendImage(oscArgs, index + 1)) return false;
            if (mEncodedImage.length() == 0) {
                setStateFailed("Empty singlepart image");
                return false;
            }
            setState(ImageState.IMAGE_READ);
            return true;
        }

        if (mCurrentState != ImageState.IMAGE_READING && !ensureState(ImageState.ARGUMENTS_READ, "readImage")) {
            return false;
        }
        setState(ImageState.IMAGE_READING);
        // Empty FilePart is valid and must not fail an otherwise valid multipart transfer.
        return appendImage(oscArgs, index);
    }

    public synchronized boolean setMultiPartImageRead() {
        if (!mIsMultipart) {
            Logger.logError(mClient, LOG_TAG, "Multipart image end for a singlepart image");
            return false;
        }
        if (!ensureState(ImageState.IMAGE_READING, "setMultiPartImageRead")) return false;
        return setState(ImageState.IMAGE_READ);
    }


    public synchronized boolean decodeImage() {
        if (!ensureState(ImageState.IMAGE_READ, "decodeImage")) return false;
        byte[] encoded = null;
        try {
            int length = mEncodedImage.length();
            if (length < 1 || length > MAX_ENCODED_IMAGE_SIZE) {
                setStateFailed("Encoded image is empty or exceeds size limit");
                return false;
            }
            // Use explicit ASCII staging instead of a UTF-16 String plus an implicit
            // getBytes() copy inside Base64.decode(String). Every allocation is admitted.
            try (TerminalImageBudget.Reservation reservation = TerminalImageBudget.GLOBAL.tryReserve(length)) {
                if (reservation == null) {
                    setStateFailed("Base64 input staging exceeds global memory budget");
                    return false;
                }
                encoded = new byte[length];
                if (!reservation.commitTo(encoded, encoded.length)) {
                    setStateFailed("Base64 input staging exceeds global memory budget");
                    return false;
                }
                for (int i = 0; i < length; i++) encoded[i] = (byte) mEncodedImage.charAt(i);
                clearEncodedImage();
            }

            // Android accepts unpadded/line-wrapped base64. Its decoder can allocate a
            // maximum-size output AND a trimmed copy, so reserve both until it returns.
            long maxDecoded = ((length + 3L) / 4) * 3;
            try (TerminalImageBudget.Reservation reservation = TerminalImageBudget.GLOBAL.tryReserve(2 * maxDecoded)) {
                if (reservation == null) {
                    setStateFailed("Base64 output staging exceeds global memory budget");
                    return false;
                }
                byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                if (decoded == null || !reservation.commitTo(decoded, decoded.length) ||
                    decoded.length < 2 || decoded.length > TerminalBitmap.MAX_IMAGE_DATA_SIZE) {
                    TerminalImageBudget.GLOBAL.release(decoded);
                    setStateFailed("Decoded image is empty or exceeds size limit");
                    return false;
                }
                mDecodedImage = decoded;
                return setState(ImageState.IMAGE_DECODED);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            setStateFailed("Failed to decode base64 image");
            return false;
        } finally {
            TerminalImageBudget.GLOBAL.release(encoded);
        }
    }

}
