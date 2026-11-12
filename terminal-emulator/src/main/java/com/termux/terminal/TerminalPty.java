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
    private int remainingAtExit = -1;
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
     * One reader owns this method. On shell exit, drain the kernel's readable tail, not an
     * unbounded stream from descendants that inherited the slave. Android available() uses
     * FIONREAD, including for PTYs, from API 24 onward:
     * https://android.googlesource.com/platform/libcore/+/android-7.0.0_r1/ojluni/src/main/native/FileInputStream.c#91
     */
    int read(FileInputStream input, byte[] buffer) throws IOException {
        if (remainingAtExit < 0) {
            try {
                Os.poll(pollDescriptors, -1);
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
            if (exit.revents != 0) remainingAtExit = input.available();
            else if ((terminal.revents & OsConstants.POLLIN) == 0) return -1;
        }
        if (remainingAtExit == 0) return -1;
        int count = input.read(buffer, 0, remainingAtExit < 0 ? buffer.length : Math.min(buffer.length, remainingAtExit));
        if (remainingAtExit > 0 && count > 0) remainingAtExit -= count;
        return count;
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
