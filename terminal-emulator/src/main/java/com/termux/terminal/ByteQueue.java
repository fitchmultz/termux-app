package com.termux.terminal;

/** A circular byte buffer allowing one producer and one consumer thread. */
final class ByteQueue {

    private final byte[] mBuffer;
    private int mHead;
    private int mStoredBytes;
    /** Capacity promised to admitted DOWN events, not a second byte queue. */
    private int mReservedBytes;
    private boolean mOpen = true;

    public ByteQueue(int size) {
        mBuffer = new byte[size];
    }

    public synchronized void close() {
        mOpen = false;
        mReservedBytes = 0;
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
        boolean wasFull = bufferLength == mStoredBytes + mReservedBytes;
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
        if (wasFull) notifyAll();
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
        if (length > mBuffer.length - mStoredBytes - mReservedBytes) return WriteResult.FULL;
        append(buffer, offset, length);
        return WriteResult.ACCEPTED;
    }

    /** A single-use promise of ring capacity, owned and guarded by its queue. */
    static final class Reservation {
        private final ByteQueue owner;
        private final int capacity;
        private boolean active;
        private boolean used;

        Reservation(ByteQueue owner, int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Reservation must be positive");
            this.owner = owner;
            this.capacity = capacity;
        }
    }

    /** Admit DOWN and its future UP capacity in one critical section, or change nothing. */
    synchronized WriteResult tryWriteAndReserve(byte[] buffer, int offset, int length, Reservation reservation) {
        checkRange(buffer, offset, length);
        checkOwner(reservation);
        if (reservation.used) throw new IllegalArgumentException("Reservation already used");
        if (!mOpen) return WriteResult.CLOSED;
        int available = mBuffer.length - mStoredBytes - mReservedBytes;
        if (reservation.capacity > available || length > available - reservation.capacity) return WriteResult.FULL;
        mReservedBytes += reservation.capacity;
        reservation.active = true;
        reservation.used = true;
        append(buffer, offset, length);
        return WriteResult.ACCEPTED;
    }

    /** Spend a promise once; unrelated writes cannot have consumed its space. */
    synchronized WriteResult tryWriteReserved(byte[] buffer, int offset, int length, Reservation reservation) {
        checkRange(buffer, offset, length);
        checkOwner(reservation);
        if (!mOpen || !reservation.active) return WriteResult.CLOSED;
        if (length > reservation.capacity) throw new IllegalArgumentException("Release exceeds reservation");
        mReservedBytes -= reservation.capacity;
        reservation.active = false;
        append(buffer, offset, length);
        notifyAll(); // Also release any unused portion to a legacy blocking producer.
        return WriteResult.ACCEPTED;
    }

    synchronized void cancel(Reservation reservation) {
        checkOwner(reservation);
        if (!reservation.active) return;
        reservation.active = false;
        if (mOpen) mReservedBytes -= reservation.capacity;
        notifyAll();
    }

    synchronized boolean isActive(Reservation reservation) {
        checkOwner(reservation);
        return mOpen && reservation.active;
    }

    private void checkOwner(Reservation reservation) {
        if (reservation.owner != this) throw new IllegalArgumentException("Reservation belongs to another queue");
    }

    /** Called only with this locked and sufficient unreserved or promised capacity. */
    private void append(byte[] buffer, int offset, int length) {
        boolean wasEmpty = mStoredBytes == 0;
        int tail = mHead + mStoredBytes;
        if (tail >= mBuffer.length) tail -= mBuffer.length;
        int firstRun = Math.min(length, mBuffer.length - tail);
        System.arraycopy(buffer, offset, mBuffer, tail, firstRun);
        if (firstRun < length) System.arraycopy(buffer, offset + firstRun, mBuffer, 0, length - firstRun);
        mStoredBytes += length;
        if (wasEmpty && length > 0) notifyAll();
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
                while (bufferLength == mStoredBytes + mReservedBytes && mOpen) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Ignore.
                    }
                }
                if (!mOpen) return false;
                final boolean wasEmpty = mStoredBytes == 0;
                int bytesToWriteBeforeWaiting = Math.min(lengthToWrite, bufferLength - mStoredBytes - mReservedBytes);
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
