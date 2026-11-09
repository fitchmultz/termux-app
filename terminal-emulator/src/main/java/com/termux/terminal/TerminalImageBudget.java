package com.termux.terminal;

import android.graphics.Bitmap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;

/**
 * App-wide admission for terminal image allocations, including unfinished images and staging.
 *
 * Weak identity keys do not prolong an allocation's lifetime. In particular a bitmap retained by
 * both a live terminal and a presentation snapshot is charged once. Bitmap charges must NOT be
 * released on terminal eviction: another terminal or an immutable snapshot may still retain it.
 * No garbage collection, recycling, background work or cross-session eviction is performed here.
 */
public final class TerminalImageBudget {

    public static final long MAX_BYTES = Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
    public static final TerminalImageBudget GLOBAL = new TerminalImageBudget(MAX_BYTES);

    private final long mMaxBytes;
    private final ReferenceQueue<Object> mQueue = new ReferenceQueue<>();
    private final HashMap<IdentityReference, Long> mCharges = new HashMap<>();
    private long mChargedBytes;

    /** An explicit limit also permits deterministic small-budget tests without filling GLOBAL. */
    public TerminalImageBudget(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid image memory budget");
        mMaxBytes = maxBytes;
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int mHash;

        IdentityReference(Object object, ReferenceQueue<Object> queue) {
            super(object, queue);
            mHash = System.identityHashCode(object);
        }

        @Override public int hashCode() {
            return mHash;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityReference)) return false;
            Object object = get();
            return object != null && object == ((IdentityReference) other).get();
        }
    }

    private void expungeCollected() {
        IdentityReference reference;
        while ((reference = (IdentityReference) mQueue.poll()) != null) {
            Long bytes = mCharges.remove(reference);
            if (bytes != null) mChargedBytes -= bytes;
        }
    }

    private long chargeOf(Object owner) {
        Long bytes = mCharges.get(new IdentityReference(owner, null));
        return bytes != null ? bytes : 0;
    }

    /**
     * Preflight a new/changed ownership charge. On rejection, the previous charge is unchanged.
     * Use actual StringBuilder capacity times two, not payload length, before growing it. For
     * growth which temporarily holds old AND new allocations, use a Reservation instead.
     */
    public synchronized boolean trySetCharge(Object owner, long bytes) {
        if (owner == null || bytes < 0) return false;
        expungeCollected();
        long oldBytes = chargeOf(owner);
        long base = mChargedBytes - oldBytes;
        if (bytes > oldBytes && (base > mMaxBytes || bytes > mMaxBytes - base)) return false;
        if (bytes == 0) {
            mCharges.remove(new IdentityReference(owner, null));
        } else {
            mCharges.put(new IdentityReference(owner, mQueue), bytes);
        }
        mChargedBytes = base + bytes;
        return true;
    }

    /** Release staging after it is cleared/dropped; never use this to evict a shared bitmap. */
    public synchronized void release(Object owner) {
        if (owner == null) return;
        expungeCollected();
        Long bytes = mCharges.remove(new IdentityReference(owner, null));
        if (bytes != null) mChargedBytes -= bytes;
    }

    public synchronized long getChargedBytes() {
        expungeCollected();
        return mChargedBytes;
    }

    public synchronized long getCharge(Object owner) {
        expungeCollected();
        return owner != null ? chargeOf(owner) : 0;
    }

    /** Reserve BEFORE allocating. The returned scope strongly owns its reservation token. */
    public Reservation tryReserve(long bytes) {
        if (bytes < 1) return null;
        Reservation reservation = new Reservation(this);
        return trySetCharge(reservation.mOwner, bytes) ? reservation : null;
    }

    private synchronized boolean commit(Reservation reservation, Object owner, long bytes) {
        if (!reservation.mActive || owner == null || bytes < 0) return false;
        expungeCollected();
        long reserved = chargeOf(reservation.mOwner);
        long previous = chargeOf(owner);
        long base = mChargedBytes - reserved - previous;
        if (bytes > Long.MAX_VALUE - base) return false;

        // Record ACTUAL memory even if a decoder exceeded its reserved estimate. Reject the
        // result, but keep its weak charge until it is collected, so repeated failures cannot
        // hide still-allocated native bitmaps. Future admissions then fail closed.
        if (bytes > 0) mCharges.put(new IdentityReference(owner, mQueue), bytes);
        else mCharges.remove(new IdentityReference(owner, null));
        mCharges.remove(new IdentityReference(reservation.mOwner, null));
        mChargedBytes = base + bytes;
        reservation.mActive = false;
        return mChargedBytes <= mMaxBytes;
    }

    private synchronized void close(Reservation reservation) {
        if (reservation.mActive) {
            release(reservation.mOwner);
            reservation.mActive = false;
        }
    }

    public static final class Reservation implements AutoCloseable {
        private final TerminalImageBudget mBudget;
        private final Object mOwner = new Object();
        private boolean mActive = true;

        private Reservation(TerminalImageBudget budget) {
            mBudget = budget;
        }

        /** Atomically replace this reservation with one identity charge for actual pixels. */
        public boolean commitToBitmap(Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled()) return false;
            return commitTo(bitmap, bitmap.getAllocationByteCount());
        }

        /** Transfer to actual encoded/decoded staging capacity, retaining any existing source. */
        public boolean commitTo(Object owner, long bytes) {
            return mBudget.commit(this, owner, bytes);
        }

        @Override public void close() {
            mBudget.close(this);
        }
    }
}
