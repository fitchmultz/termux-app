package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * When the size is first made known by {@link #updateSize(int, int, int, int)}, terminal emulation
 * and the subprocess/I/O threads are started. The constructor starts no process or worker.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;
    private static final int MSG_SYNCHRONIZED_OUTPUT_TIMEOUT = 5;

    /** Match Kitty's bounded recovery for an application that never sends CSI ? 2026 l. */
    static final long SYNCHRONIZED_OUTPUT_TIMEOUT_MILLIS = 2000;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** Whether a {@link #MSG_NEW_INPUT} wakeup is queued for the main thread. */
    private final AtomicBoolean mNewInputMessagePending = new AtomicBoolean();
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    // Preserve PR #5409's 4 MiB pending-input policy, not its extra worker/job queue.
    // https://github.com/black1771/termux-app/blob/1b5b0f11f238086fbf3a556e72c297f9a6498195/terminal-emulator/src/main/java/com/termux/terminal/ByblackUnifiedInput.java#L9
    // This is a fixed per-session byte ring, not a total-heap/RSS or paste-staging guarantee.
    static final int MAX_PENDING_INPUT_BYTES = 4 * 1024 * 1024;
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(MAX_PENDING_INPUT_BYTES);

    public enum InputRejectionReason { FULL, CLOSED }
    /**
     * The rejection already reported in the current episode, or {@code null} when no episode is
     * open. An episode stays open until input is accepted or the client changes, so a rejected key
     * is reported once. Guarded by this.
     */
    private InputRejectionReason mReportedRejection;
    /** Metadata only; positive reservations bound this set by the existing input-ring capacity. */
    private final ArrayList<InputReservation> mInputReservations = new ArrayList<>();

    /** One future release, bound to its original session, screen and keyboard generation. */
    public static final class InputReservation {
        private final TerminalSession session;
        private final ByteQueue.Reservation reservation;
        private final TerminalEmulator emulator;
        private final Object screen;
        private final long generation;

        private InputReservation(TerminalSession session, int capacity) {
            this.session = session;
            reservation = new ByteQueue.Reservation(session.mTerminalToProcessIOQueue, capacity);
            emulator = session.mEmulator;
            screen = emulator == null ? null : emulator.getScreen();
            generation = emulator == null ? 0 : emulator.getKittyKeyboardGeneration();
        }

        private boolean isCurrent() {
            return emulator != null && session.mEmulator == emulator && emulator.getScreen() == screen &&
                emulator.getKittyKeyboardGeneration() == generation;
        }

        /**
         * Append the matching release once, without waiting for a PTY consumer. An oversized
         * release throws before changing bytes/capacity; the owner may retry or cancel the promise.
         */
        public boolean writeRelease(String text) { return session.writeReservedRelease(this, text); }

        /** Return unused capacity without delivering into an invalidated input context. */
        public void cancel() { session.cancelInputReservation(this); }
    }
    /**
     * Lifecycle of the shell process and of the input admission that depends on it.
     *
     * <p>{@link #CREATED}: no emulator and no process. {@link #STARTING}: setup is in progress, so
     * input is rejected and callbacks wait. {@link #RUNNING}: the process exists and input is
     * accepted. {@link #DRAINING}: the process was reaped, input is closed and the reader is
     * publishing its final output. {@link #EXITED}: output is complete and exit status is valid. {@link #FAILED}:
     * setup threw; the same cleanup ran, and the waiter must not publish a second UI exit.
     *
     * <p>{@link #CANCELLED} is a session finished before its shell ever started: nothing was
     * signalled, and it must stop reporting itself as running.
     *
     * <p>Only {@code STARTING → RUNNING/FAILED}, {@code RUNNING → DRAINING → EXITED} and
     * {@code CREATED → CANCELLED} happen.
     */
    public enum ShellState { CREATED, STARTING, RUNNING, DRAINING, EXITED, FAILED, CANCELLED }
    private volatile ShellState mShellState = ShellState.CREATED;
    /** UTF-8 scratch for one code point; guarded by this until copied into the input ring. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    volatile int mShellPid;

    /** The exit status of the shell process. Only valid once {@link #isRunning()} is false. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor = -1;
    private TerminalPty mPty;
    private Thread mInputReader;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        synchronized (this) {
            mClient = client;
            mReportedRejection = null;
        }

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        int startedPid = -1;
        final int[] processId = new int[1];
        synchronized (this) {
            if (mShellState == ShellState.CANCELLED) return;
            mShellState = ShellState.STARTING;
        }
        try {
            // Start the existing waiter before allocating native resources, not in the constructor.
            // Its allocation/start can fail without leaving a child that needs a new cleanup worker.
            final Thread waiter = new Thread("TermSessionWaiter") {
                @Override
                public void run() {
                    // Do not reap/release the PID until setup has either succeeded or requested
                    // failure termination. Otherwise a failing callback could signal a reused PID.
                    boolean interrupted = false;
                    synchronized (TerminalSession.this) {
                        while (mShellState == ShellState.STARTING) {
                            try {
                                TerminalSession.this.wait();
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                    }
                    final int pid = processId[0];
                    if (pid > 0) onProcessExit(JNI.waitFor(pid));
                    if (interrupted) Thread.currentThread().interrupt();
                }
            };
            waiter.start();
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
            startedPid = processId[0];
            mShellPid = startedPid;
            if (mTerminalFileDescriptor < 0 || startedPid <= 0)
                throw new IllegalStateException("Terminal subprocess did not start");

            final int pid = startedPid;
            waiter.setName("TermSessionWaiter[pid=" + pid + "]");
            mClient.setTerminalShellPid(this, pid);
            final TerminalPty pty = mPty = new TerminalPty(mTerminalFileDescriptor);
            pty.prepareReader();
            final FileDescriptor terminalFileDescriptorWrapped = pty.getFileDescriptor();

            mInputReader = new Thread("TermSessionInputReader[pid=" + pid + "]") {
                @Override
                public void run() {
                    try (FileInputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                        final byte[] buffer = new byte[4096];
                        while (true) {
                            int read = pty.read(termIn, buffer);
                            if (read == -1) return;
                            if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                            scheduleInputProcessing();
                        }
                    } catch (Exception e) {
                        // Ignore, just shutting down.
                    }
                }
            };
            mInputReader.start();

            new Thread("TermSessionOutputWriter[pid=" + pid + "]") {
                @Override
                public void run() {
                    try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                        writeInputTo(termOut);
                    } catch (IOException e) {
                        // A failed write closes admission and reports rejection in writeInputTo().
                    } finally {
                        mTerminalToProcessIOQueue.close();
                    }
                }
            }.start();
        } catch (Throwable failure) {
            mShellState = ShellState.FAILED;
            // A failed native call may partially fill PID output without transferring child
            // ownership. Do not wait on it; native pre-return cleanup semantics are unchanged.
            if (startedPid <= 0) processId[0] = 0;
            if (startedPid > 0) {
                try {
                    Os.kill(startedPid, OsConstants.SIGKILL);
                } catch (Throwable secondary) {
                    // Keep the original setup failure, but still attempt all remaining cleanup.
                    suppressCleanupFailure(failure, secondary);
                }
            }
            try {
                cleanupResources(1);
            } catch (Throwable secondary) {
                suppressCleanupFailure(failure, secondary);
            }
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("Cannot prepare terminal PTY reader", failure);
        } finally {
            synchronized (this) {
                if (mShellState == ShellState.STARTING) mShellState = ShellState.RUNNING;
                notifyAll();
            }
        }
    }

    /** The native waiter owns exit status; completion also waits for the reader's final bytes. */
    private void onProcessExit(int exitCode) {
        final TerminalPty pty;
        final Thread inputReader;
        synchronized (this) {
            if (mShellState != ShellState.FAILED) {
                mShellState = ShellState.DRAINING;
                // waitpid released this PID: neither input nor a later stop may target it again.
                mShellPid = -1;
            }
            pty = mPty;
            inputReader = mInputReader;
        }
        if (pty != null) {
            try {
                pty.processExited();
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error signalling PTY reader", e);
            }
        }
        boolean interrupted = false;
        if (inputReader != null) {
            // Main keeps draining the bounded queue while the existing waiter waits off-main.
            while (inputReader.isAlive()) {
                try {
                    inputReader.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        mInputReader = null;
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, exitCode));
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void suppressCleanupFailure(Throwable original, Throwable secondary) {
        if (original == secondary) return;
        try {
            original.addSuppressed(secondary);
        } catch (Throwable ignored) {
            // Even failure to record a secondary error must not replace the original failure.
        }
    }

    /** The existing PTY writer consumes the same FIFO; this method starts no thread. */
    void writeInputTo(OutputStream output) throws IOException {
        final byte[] buffer = new byte[4096];
        try {
            while (true) {
                int count = mTerminalToProcessIOQueue.read(buffer, true);
                if (count == -1) return;
                output.write(buffer, 0, count);
            }
        } catch (IOException failure) {
            mTerminalToProcessIOQueue.close();
            reportInputRejected(InputRejectionReason.CLOSED);
            throw failure;
        } finally {
            mTerminalToProcessIOQueue.close();
        }
    }

    /** Write data without waiting for the PTY consumer. Rejection is reported to the client. */
    @Override
    public void write(byte[] data, int offset, int count) {
        tryWrite(data, offset, count);
    }

    /** An admission receipt for callers that track a key press or other input-side state. */
    public boolean tryWrite(String text) {
        if (text == null) return true;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return tryWrite(data, 0, data.length);
    }

    /** Admit all bytes or none. Accepted events retain FIFO order, including terminal replies. */
    public boolean tryWrite(byte[] data, int offset, int count) {
        ByteQueue.checkRange(data, offset, count);
        if (count == 0) return true;
        ByteQueue.WriteResult result;
        InputRejectionReason report;
        synchronized (this) {
            result = admitInputLocked(data, offset, count);
            report = claimInputAdmissionLocked(result);
        }
        return finishInputAdmission(result, report);
    }

    /** Admit DOWN only if both its bytes and a positive future-release promise fit. */
    public InputReservation tryWriteAndReserveRelease(String text, int releaseBytes) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        InputReservation reservation;
        ByteQueue.WriteResult result;
        InputRejectionReason report;
        synchronized (this) {
            reservation = new InputReservation(this, releaseBytes);
            result = admitInputLocked(data, 0, data.length, reservation);
            report = claimInputAdmissionLocked(result);
        }
        finishInputAdmission(result, report);
        return result == ByteQueue.WriteResult.ACCEPTED ? reservation : null;
    }

    private boolean isAcceptingInputLocked() {
        return mShellState == ShellState.RUNNING;
    }

    /** Called with this locked, including while the code-point scratch buffer is in use. */
    private ByteQueue.WriteResult admitInputLocked(byte[] data, int offset, int count) {
        return admitInputLocked(data, offset, count, null);
    }

    private ByteQueue.WriteResult admitInputLocked(byte[] data, int offset, int count, InputReservation reservation) {
        pruneInputReservationsLocked();
        if (!isAcceptingInputLocked() || (reservation != null && !reservation.isCurrent()))
            return ByteQueue.WriteResult.CLOSED;
        if (reservation == null) return mTerminalToProcessIOQueue.tryWrite(data, offset, count);
        // Allocate tracking metadata before DOWN admission, not after bytes have been accepted.
        mInputReservations.add(reservation);
        ByteQueue.WriteResult result = mTerminalToProcessIOQueue.tryWriteAndReserve(data, offset, count, reservation.reservation);
        if (result != ByteQueue.WriteResult.ACCEPTED) mInputReservations.remove(reservation);
        return result;
    }

    /** Protocol/screen changes reclaim promises before any ordinary input can be rejected. */
    private void pruneInputReservationsLocked() {
        Iterator<InputReservation> iterator = mInputReservations.iterator();
        while (iterator.hasNext()) {
            InputReservation reservation = iterator.next();
            if (!reservation.isCurrent() || !mTerminalToProcessIOQueue.isActive(reservation.reservation)) {
                mTerminalToProcessIOQueue.cancel(reservation.reservation);
                iterator.remove();
            }
        }
    }

    private synchronized void cancelInputReservation(InputReservation reservation) {
        mTerminalToProcessIOQueue.cancel(reservation.reservation);
        mInputReservations.remove(reservation);
    }

    private boolean writeReservedRelease(InputReservation reservation, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteQueue.WriteResult result;
        InputRejectionReason report;
        synchronized (this) {
            pruneInputReservationsLocked();
            if (isAcceptingInputLocked() && reservation.isCurrent()) {
                result = mTerminalToProcessIOQueue.tryWriteReserved(data, 0, data.length, reservation.reservation);
            } else {
                mTerminalToProcessIOQueue.cancel(reservation.reservation);
                result = ByteQueue.WriteResult.CLOSED;
            }
            mInputReservations.remove(reservation);
            report = claimInputAdmissionLocked(result);
        }
        return finishInputAdmission(result, report);
    }

    /** Claim notification in the same critical section as admission, not after another writer. */
    private InputRejectionReason claimInputAdmissionLocked(ByteQueue.WriteResult result) {
        if (result == ByteQueue.WriteResult.ACCEPTED) {
            mReportedRejection = null;
            return null;
        }
        return claimInputRejectionLocked(result == ByteQueue.WriteResult.FULL ? InputRejectionReason.FULL : InputRejectionReason.CLOSED);
    }

    private InputRejectionReason claimInputRejectionLocked(InputRejectionReason reason) {
        if (mReportedRejection == reason) return null;
        mReportedRejection = reason;
        return reason;
    }

    private boolean finishInputAdmission(ByteQueue.WriteResult result, InputRejectionReason report) {
        deliverInputRejection(report);
        return result == ByteQueue.WriteResult.ACCEPTED;
    }

    private void reportInputRejected(InputRejectionReason reason) {
        InputRejectionReason report;
        synchronized (this) { report = claimInputRejectionLocked(reason); }
        deliverInputRejection(report);
    }

    private void deliverInputRejection(InputRejectionReason reason) {
        if (reason == null) return;
        Runnable report = () -> mClient.onInputRejected(this, reason);
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) report.run();
        else mMainThreadHandler.post(report);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        tryWriteCodePoint(prependEscape, codePoint);
    }

    /** Admission receipt for the exact UTF-8/optional-ESC event, without inventing a key identity. */
    public boolean tryWriteCodePoint(boolean prependEscape, int codePoint) {
        return tryWriteCodePoint(prependEscape, codePoint, null);
    }

    public InputReservation tryWriteCodePointAndReserveRelease(boolean prependEscape, int codePoint, int releaseBytes) {
        final InputReservation reservation;
        synchronized (this) { reservation = new InputReservation(this, releaseBytes); }
        return tryWriteCodePoint(prependEscape, codePoint, reservation) ? reservation : null;
    }

    private boolean tryWriteCodePoint(boolean prependEscape, int codePoint, InputReservation reservation) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        final ByteQueue.WriteResult result;
        final InputRejectionReason report;
        synchronized (this) {
            int bufferPosition = 0;
            if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

            if (codePoint <= /* 7 bits */0b1111111) {
                mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
            } else if (codePoint <= /* 11 bits */0b11111111111) {
                /* 110xxxxx leading byte with leading 5 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
                /* 10xxxxxx continuation byte with following 6 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
            } else if (codePoint <= /* 16 bits */0b1111111111111111) {
                /* 1110xxxx leading byte with leading 4 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
                /* 10xxxxxx continuation byte with following 6 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
                /* 10xxxxxx continuation byte with following 6 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
            } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
                /* 11110xxx leading byte with leading 3 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
                /* 10xxxxxx continuation byte with following 6 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
                /* 10xxxxxx continuation byte with following 6 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
                /* 10xxxxxx continuation byte with following 6 bits */
                mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
            }
            result = admitInputLocked(mUtf8InputBuffer, 0, bufferPosition, reservation);
            report = claimInputAdmissionLocked(result);
        }
        return finishInputAdmission(result, report);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        if (mEmulator != null && mEmulator.isSynchronizedOutputActive()) {
            if (!mMainThreadHandler.hasMessages(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT))
                mMainThreadHandler.sendEmptyMessageDelayed(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT, SYNCHRONIZED_OUTPUT_TIMEOUT_MILLIS);
            return;
        }

        mMainThreadHandler.removeMessages(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT);
        // A palette update deferred by the frame that just closed is delivered with the frame.
        if (mEmulator != null && mEmulator.takeDeferredPaletteChange()) mClient.onColorsChanged(this);
        mClient.onTextChanged(this);
    }

    /** Force presentation after the synchronized-output watchdog expires. Package-private for tests. */
    void handleSynchronizedOutputTimeout() {
        mMainThreadHandler.removeMessages(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT);
        if (mEmulator == null || !mEmulator.isSynchronizedOutputActive()) return;
        mEmulator.finishSynchronizedOutput();
        notifyScreenUpdate();
    }

    private void finishSynchronizedOutput() {
        mMainThreadHandler.removeMessages(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT);
        if (mEmulator != null) mEmulator.finishSynchronizedOutput();
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mMainThreadHandler.removeMessages(MSG_SYNCHRONIZED_OUTPUT_TIMEOUT);
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        final int pid;
        final boolean cancelledBeforeStart;
        synchronized (this) {
            pid = mShellPid;
            // Nothing to signal before a shell starts, and pid 0 would target our own process group.
            cancelledBeforeStart = mShellState == ShellState.CREATED;
            if (cancelledBeforeStart) {
                mShellState = ShellState.CANCELLED;
                cleanupResources(128 + OsConstants.SIGKILL);
            }
        }
        if (cancelledBeforeStart) {
            mClient.onSessionFinished(this);
            return;
        }
        if (pid <= 0) return;
        try {
            Os.kill(pid, OsConstants.SIGKILL);
        } catch (ErrnoException e) {
            Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        final int descriptor;
        final TerminalPty pty;
        synchronized (this) {
            for (InputReservation reservation : mInputReservations)
                mTerminalToProcessIOQueue.cancel(reservation.reservation);
            mInputReservations.clear();
            mShellPid = -1;
            mShellExitStatus = exitStatus;
            // Failed setup already recorded its own terminal state.
            if (mShellState != ShellState.FAILED && mShellState != ShellState.CANCELLED)
                mShellState = ShellState.EXITED;
            descriptor = mTerminalFileDescriptor;
            mTerminalFileDescriptor = -1;
            pty = mPty;
            mPty = null;
        }

        // Stop the reader and writer threads, and close the I/O streams.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        if (pty != null) {
            try {
                pty.close();
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error closing terminal PTY", e);
            }
        } else if (descriptor >= 0) {
            // Setup has not transferred the raw native descriptor to its Android owner yet.
            JNI.close(descriptor);
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        // Completion includes final output delivery, even after waitpid has reaped the shell.
        return mShellState != ShellState.EXITED && mShellState != ShellState.FAILED &&
            mShellState != ShellState.CANCELLED;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mEmulator != null && mEmulator.deferPaletteChange()) return;
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    /** Schedule a queue drain after the PTY reader has enqueued input. Package-private for tests. */
    void scheduleInputProcessing() {
        if (mNewInputMessagePending.compareAndSet(false, true) &&
            !mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT))
            mNewInputMessagePending.set(false);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            if (mShellState == ShellState.FAILED) return;
            if (msg.what == MSG_SYNCHRONIZED_OUTPUT_TIMEOUT) {
                handleSynchronizedOutputTimeout();
                return;
            }

            // Clear this before reading so input written concurrently after the queue is drained
            // can schedule the next wakeup. Only one MSG_NEW_INPUT remains queued at a time without
            // scanning the main thread's MessageQueue. The receive buffer matches the queue capacity.
            if (msg.what == MSG_NEW_INPUT) mNewInputMessagePending.set(false);

            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                finishSynchronizedOutput();
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
