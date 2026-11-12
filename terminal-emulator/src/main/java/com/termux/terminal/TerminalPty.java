package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Owns the master descriptor and the reader's process-exit wakeup pipe. Streams borrow the same
 * FileDescriptor; closing the PFD invalidates it and wakes Android's blocked-I/O monitors.
 * https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/os/ParcelFileDescriptor.java#686
 * https://android.googlesource.com/platform/libcore/+/android-7.0.0_r1/luni/src/main/java/libcore/io/IoBridge.java#196
 */
final class TerminalPty implements Closeable {
    private final ParcelFileDescriptor master;
    private ParcelFileDescriptor[] processExitPipe;
    private boolean processHasExited;
    private final StructPollfd terminal = new StructPollfd();
    private final StructPollfd exit = new StructPollfd();
    private final StructPollfd[] pollDescriptors = { terminal, exit };

    TerminalPty(int descriptor) {
        master = ParcelFileDescriptor.adoptFd(descriptor);
    }

    void prepareReader() throws IOException {
        processExitPipe = ParcelFileDescriptor.createPipe();
        terminal.fd = getFileDescriptor();
        terminal.events = (short) OsConstants.POLLIN;
        exit.fd = processExitPipe[0].getFileDescriptor();
        exit.events = (short) OsConstants.POLLIN;
    }

    FileDescriptor getFileDescriptor() {
        return master.getFileDescriptor();
    }

    /** The waiter closes only the wakeup writer, never the PTY being read. */
    synchronized void processExited() throws IOException {
        if (processExitPipe != null) processExitPipe[1].close();
    }

    /**
     * One reader owns this method. After shell exit, read while the PTY is ready without
     * waiting for inherited slave holders to reach EOF. Active descendant output can continue
     * while readable. Poll again after every read: FIONREAD only counts the line discipline's
     * current buffer, whereas n_tty_poll flushes pending flip-buffer work when that buffer empties.
     * https://github.com/torvalds/linux/blob/v6.18/drivers/tty/n_tty.c#L2445-L2460
     * https://github.com/torvalds/linux/blob/v6.18/drivers/tty/n_tty.c#L2500-L2511
     */
    int read(FileInputStream input, byte[] buffer) throws IOException {
        try {
            if (!processHasExited) {
                Os.poll(pollDescriptors, -1);
                processHasExited = exit.revents != 0;
            }
            // The first poll may have inspected the PTY before the exit pipe became ready.
            if (processHasExited) Os.poll(pollDescriptors, 0);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
        if ((terminal.revents & OsConstants.POLLIN) == 0) return -1;
        return input.read(buffer);
    }

    @Override
    public synchronized void close() throws IOException {
        try (ParcelFileDescriptor ignoredMaster = master;
             ParcelFileDescriptor ignoredRead = processExitPipe == null ? null : processExitPipe[0];
             ParcelFileDescriptor ignoredWrite = processExitPipe == null ? null : processExitPipe[1]) {
            // Closing all three resources also covers partially initialized setup.
        }
    }
}
