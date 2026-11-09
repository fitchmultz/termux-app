package com.termux.terminal;

/** A circular byte buffer allowing one producer and one consumer thread. */
final class ByteQueue {

    private final byte[] mBuffer;
    private int mHead;
    private int mStoredBytes;
    private boolean mOpen = true;

    public ByteQueue(int size) {
        mBuffer = new byte[size];
    }

    public synchronized void close() {
        mOpen = false;
        notifyAll();
    }

    public synchronized int read(byte[] buffer, boolean block) {
        while (mStoredBytes == 0 && mOpen) {
            if (block) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            } else {
                return 0;
            }
        }
        if (!mOpen) return -1;

        int totalRead = 0;
        int bufferLength = mBuffer.length;
        boolean wasFull = bufferLength == mStoredBytes;
        int length = buffer.length;
        int offset = 0;
        while (length > 0 && mStoredBytes > 0) {
            int oneRun = Math.min(bufferLength - mHead, mStoredBytes);
            int bytesToCopy = Math.min(length, oneRun);
            System.arraycopy(mBuffer, mHead, buffer, offset, bytesToCopy);
            mHead += bytesToCopy;
            if (mHead >= bufferLength) mHead = 0;
            mStoredBytes -= bytesToCopy;
            length -= bytesToCopy;
            offset += bytesToCopy;
            totalRead += bytesToCopy;
        }
        if (wasFull) notify();
        return totalRead;
    }

    enum WriteResult { ACCEPTED, FULL, CLOSED }

    /**
     * Admit an entire input event without waiting for the consumer. A rejected event writes
     * nothing, including when it would fit only partly or would wrap around the ring.
     */
    public synchronized WriteResult tryWrite(byte[] buffer, int offset, int length) {
        checkRange(buffer, offset, length);
        if (length == 0) return WriteResult.ACCEPTED;
        if (!mOpen) return WriteResult.CLOSED;
        if (length > mBuffer.length - mStoredBytes) return WriteResult.FULL;

        boolean wasEmpty = mStoredBytes == 0;
        int tail = mHead + mStoredBytes;
        if (tail >= mBuffer.length) tail -= mBuffer.length;
        int firstRun = Math.min(length, mBuffer.length - tail);
        System.arraycopy(buffer, offset, mBuffer, tail, firstRun);
        if (firstRun < length) System.arraycopy(buffer, offset + firstRun, mBuffer, 0, length - firstRun);
        mStoredBytes += length;
        if (wasEmpty) notifyAll();
        return WriteResult.ACCEPTED;
    }

    static void checkRange(byte[] buffer, int offset, int length) {
        if (offset < 0 || length < 0 || offset > buffer.length || length > buffer.length - offset)
            throw new IllegalArgumentException("Invalid offset or length");
    }

    /**
     * Attempt to write the specified portion of the provided buffer to the queue.
     * <p/>
     * Returns whether the output was totally written, false if it was closed before.
     */
    public boolean write(byte[] buffer, int offset, int lengthToWrite) {
        checkRange(buffer, offset, lengthToWrite);
        if (lengthToWrite == 0) {
            // A zero-length write is a no-op. This can happen when pasting text
            // that TerminalEmulator.paste() fully strips of escape/control
            // characters, which previously crashed the app (issue #5119).
            return true;
        }

        final int bufferLength = mBuffer.length;

        synchronized (this) {
            while (lengthToWrite > 0) {
                while (bufferLength == mStoredBytes && mOpen) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Ignore.
                    }
                }
                if (!mOpen) return false;
                final boolean wasEmpty = mStoredBytes == 0;
                int bytesToWriteBeforeWaiting = Math.min(lengthToWrite, bufferLength - mStoredBytes);
                lengthToWrite -= bytesToWriteBeforeWaiting;

                while (bytesToWriteBeforeWaiting > 0) {
                    int tail = mHead + mStoredBytes;
                    int oneRun;
                    if (tail >= bufferLength) {
                        // Buffer: [.............]
                        // ________________H_______T
                        // =>
                        // Buffer: [.............]
                        // ___________T____H
                        // onRun= _____----_
                        tail = tail - bufferLength;
                        oneRun = mHead - tail;
                    } else {
                        oneRun = bufferLength - tail;
                    }
                    int bytesToCopy = Math.min(oneRun, bytesToWriteBeforeWaiting);
                    System.arraycopy(buffer, offset, mBuffer, tail, bytesToCopy);
                    offset += bytesToCopy;
                    bytesToWriteBeforeWaiting -= bytesToCopy;
                    mStoredBytes += bytesToCopy;
                }
                if (wasEmpty) notify();
            }
        }
        return true;
    }
}
