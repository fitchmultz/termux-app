package com.termux.terminal;

import android.app.Application;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowParcelFileDescriptor;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Queue/production-writer-loop tests; JNI and process signals are mocked, not native PTY evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, application = Application.class, shadows = {
    TerminalSessionWriteTest.NoPty.class, TerminalSessionWriteTest.NoSignals.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalSessionWriteTest {
    private final List<TerminalSession> created = new ArrayList<>();
    private Client client;
    private TerminalSession session;

    @Implements(value = JNI.class, isInAndroidSdk = false)
    public static class NoPty {
        static RuntimeException createFailure, closeFailure;
        static int createCalls, returnedPid, returnedDescriptor;
        static CountDownLatch waited;
        static volatile Thread waiter;
        static final List<Integer> closed = Collections.synchronizedList(new ArrayList<>());
        static final List<Integer> waitedPids = Collections.synchronizedList(new ArrayList<>());
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static int createSubprocess(String cmd, String cwd, String[] args,
                String[] env, int[] pid, int rows, int columns, int cellWidth, int cellHeight) {
            createCalls++;
            if (createFailure != null) throw createFailure;
            pid[0] = returnedPid;
            return returnedDescriptor;
        }
        @Implementation protected static void close(int fd) {
            closed.add(fd);
            if (closeFailure != null) throw closeFailure;
        }
        @Implementation protected static int waitFor(int pid) {
            waiter = Thread.currentThread();
            waitedPids.add(pid);
            waited.countDown();
            return 0;
        }
    }

    @Implements(Os.class)
    public static class NoSignals {
        static RuntimeException killFailure;
        static final List<Integer> killedPids = new ArrayList<>();
        static final List<Integer> signals = new ArrayList<>();
        @Implementation protected static void kill(int pid, int signal) {
            killedPids.add(pid);
            signals.add(signal);
            if (killFailure != null) throw killFailure;
        }
    }

    private static class Client implements TerminalSessionClient {
        final List<TerminalSession.InputRejectionReason> rejected = new ArrayList<>();
        final List<TerminalSession> rejectedSessions = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        RuntimeException pidFailure;
        boolean probeAtPid;
        Boolean probeAccepted;
        int finished;
        @Override public void onInputRejected(TerminalSession s, TerminalSession.InputRejectionReason reason) {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            assertFalse("Client callback must not run under the session lock", Thread.holdsLock(s));
            rejected.add(reason);
            rejectedSessions.add(s);
            TerminalSessionClient.super.onInputRejected(s, reason);
        }
        @Override public void setTerminalShellPid(TerminalSession s, int pid) {
            if (probeAtPid) probeAccepted = s.tryWrite("not-ready input");
            if (pidFailure != null) throw pidFailure;
        }
        @Override public void onTextChanged(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onSessionFinished(TerminalSession s) { finished++; }
        @Override public void onCopyTextToClipboard(TerminalSession s, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onColorsChanged(TerminalSession s) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) { warnings.add(message); }
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }

    private TerminalSession fresh(Client c) {
        TerminalSession s = new TerminalSession("/unused", "/", new String[0], new String[0], 100, c);
        created.add(s);
        return s;
    }

    private TerminalSession fixture(Client c) {
        TerminalSession s = fresh(c);
        s.mEmulator = new TerminalEmulator(s, 80, 24, 8, 16, 100, c);
        // Preserve the existing IME/pane fixture contract: no real child or transport worker.
        s.mShellPid = 1;
        ReflectionHelpers.setField(s, "mShellState", TerminalSession.ShellState.RUNNING);
        return s;
    }

    @Test public void finishingBeforeStartNotifiesOnceAndCannotStartLater() {
        TerminalSession cancelled = fresh(client);
        cancelled.finishIfRunning();
        assertFalse(cancelled.isRunning());
        assertEquals(137, cancelled.getExitStatus());
        assertEquals(1, client.finished);
        assertTrue(NoSignals.killedPids.isEmpty());
        assertFalse(cancelled.tryWrite("ignored"));
        cancelled.finishIfRunning();
        cancelled.updateSize(80, 24, 8, 16);
        cancelled.initializeEmulator(80, 24, 8, 16);
        assertEquals(1, client.finished);
        assertEquals(0, NoPty.createCalls);
        assertNull(cancelled.getEmulator());
    }

    @Before public void setUp() {
        NoPty.createFailure = new IllegalStateException("mock native initialization failed");
        NoPty.createCalls = 0;
        NoPty.closeFailure = null;
        NoSignals.killFailure = null;
        NoPty.returnedPid = 0;
        NoPty.returnedDescriptor = -1;
        NoPty.closed.clear();
        NoPty.waitedPids.clear();
        NoPty.waited = new CountDownLatch(1);
        NoPty.waiter = null;
        NoSignals.killedPids.clear();
        NoSignals.signals.clear();
        client = new Client();
        session = fixture(client);
    }

    @After public void tearDown() {
        for (TerminalSession s : created) s.cleanupResources(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static byte[] drain(TerminalSession s) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        int count;
        while ((count = s.mTerminalToProcessIOQueue.read(bytes, false)) > 0) output.write(bytes, 0, count);
        return output.toByteArray();
    }

    private static void append(TerminalSession s, String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        s.mEmulator.append(bytes, bytes.length);
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue("Test worker watchdog", workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test public void sanitizedFramedPasteKeyAndTerminalReplyShareOneFifo() {
        append(session, "\033[?2004h");
        session.mEmulator.paste("A\033B\u0080C\r\n汉🙂\nD");
        assertTrue(session.tryWriteCodePoint(true, 'é'));
        append(session, "\033[6n");
        assertArrayEquals(("\033[200~ABC\r汉🙂\rD\033[201~\033é\033[1;1R")
            .getBytes(StandardCharsets.UTF_8), drain(session));
        assertTrue(client.rejected.isEmpty());
    }

    @Test public void utf8CodePointReceiptsPreserveLiteralControlsAndSupplementaryCharacters() {
        assertTrue(session.tryWriteCodePoint(false, '\r'));
        assertTrue(session.tryWriteCodePoint(false, '\n'));
        assertTrue(session.tryWriteCodePoint(true, 0x1f642));
        assertArrayEquals("\r\n\033🙂".getBytes(StandardCharsets.UTF_8), drain(session));
    }

    @Test public void largePasteFitsSourceBackedCapacityWithoutWriterOrConsumer() {
        String text = "汉🙂".repeat(256 * 1024);
        append(session, "\033[?2004h");
        session.mEmulator.paste(text);
        assertArrayEquals(("\033[200~" + text + "\033[201~").getBytes(StandardCharsets.UTF_8), drain(session));
        assertEquals(0, NoPty.createCalls);
        assertTrue(client.rejected.isEmpty());
    }

    @Test public void insufficientSpaceRejectsWholePasteEvenWhenPrefixWouldFit() {
        byte[] pending = new byte[TerminalSession.MAX_PENDING_INPUT_BYTES - 8];
        Arrays.fill(pending, (byte) 'P');
        assertTrue(session.tryWrite(pending, 0, pending.length));
        append(session, "\033[?2004h");
        session.mEmulator.paste("ok"); // 14 framed bytes; eight free bytes would fit an orphan prefix.
        assertArrayEquals(pending, drain(session));
        assertEquals(Collections.singletonList(TerminalSession.InputRejectionReason.FULL), client.rejected);
    }

    @Test public void oversizedPasteRejectsBodyAndBothMarkersWithoutChangingEarlierInput() {
        assertTrue(session.tryWrite("kept"));
        append(session, "\033[?2004h");
        session.mEmulator.paste("x".repeat(TerminalSession.MAX_PENDING_INPUT_BYTES));
        assertArrayEquals("kept".getBytes(StandardCharsets.UTF_8), drain(session));
        assertEquals(Collections.singletonList(TerminalSession.InputRejectionReason.FULL), client.rejected);
    }

    @Test public void emptySanitizedInputRetainsLegacyBracketAndNoopBytes() {
        session.mEmulator.paste("\033\u0080");
        assertEquals(0, drain(session).length);
        append(session, "\033[?2004h");
        session.mEmulator.paste("\033\u0080");
        assertArrayEquals("\033[200~\033[201~".getBytes(StandardCharsets.UTF_8), drain(session));
        session.cleanupResources(0);
        assertTrue(session.tryWrite(""));
        assertTrue(session.tryWrite((String) null));
        assertTrue(client.rejected.isEmpty());
    }

    @Test public void fullQueueReceiptsAndCoalescingRearmAfterSuccessWithoutPayloadLogs() {
        byte[] pending = new byte[TerminalSession.MAX_PENDING_INPUT_BYTES];
        assertTrue(session.tryWrite(pending, 0, pending.length));
        assertFalse(session.tryWrite("PRIVATE_REJECTED_TEXT"));
        assertFalse(session.tryWriteCodePoint(true, 0x1f642));
        session.writeCodePoint(false, 'z');
        assertEquals(1, client.rejected.size());
        assertFalse(client.warnings.get(0).contains("PRIVATE_REJECTED_TEXT"));
        assertEquals(1, session.mTerminalToProcessIOQueue.read(new byte[1], false));
        assertTrue(session.tryWrite("a"));
        assertFalse(session.tryWrite("b"));
        assertEquals(2, client.rejected.size());
        byte[] accepted = drain(session);
        assertEquals(TerminalSession.MAX_PENDING_INPUT_BYTES, accepted.length);
        assertEquals('a', accepted[accepted.length - 1]);
    }

    @Test public void successRearmsWhilePriorNotificationIsStillQueued() throws Exception {
        byte[] pending = new byte[TerminalSession.MAX_PENDING_INPUT_BYTES];
        assertTrue(session.tryWrite(pending, 0, pending.length));
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            assertFalse(producer.submit(() -> session.tryWrite("first rejection")).get(5, TimeUnit.SECONDS));
            assertTrue(client.rejected.isEmpty());
            assertEquals(1, session.mTerminalToProcessIOQueue.read(new byte[1], false));
            assertTrue(session.tryWrite("a"));
            assertFalse(session.tryWrite("second rejection"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Arrays.asList(TerminalSession.InputRejectionReason.FULL,
                TerminalSession.InputRejectionReason.FULL), client.rejected);
        } finally {
            session.mTerminalToProcessIOQueue.close();
            stop(producer);
        }
    }

    @Test public void blockedPtyConsumerDoesNotBlockFullSessionAdmission() throws Exception {
        CountDownLatch inWrite = new CountDownLatch(1), allowWrite = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("Use the byte-array writer"); }
            @Override public void write(byte[] data, int offset, int count) throws IOException {
                inWrite.countDown();
                try {
                    if (!allowWrite.await(10, TimeUnit.SECONDS)) throw new IOException("Consumer watchdog");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        };
        byte[] pending = new byte[TerminalSession.MAX_PENDING_INPUT_BYTES];
        assertTrue(session.tryWrite(pending, 0, pending.length));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = workers.submit(() -> { session.writeInputTo(blocked); return null; });
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            assertTrue(session.tryWrite(new byte[4096], 0, 4096));
            Future<Boolean> admission = workers.submit(() -> session.tryWrite("rejected while PTY is blocked"));
            assertFalse(admission.get(5, TimeUnit.SECONDS));
            assertEquals(1, allowWrite.getCount());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Collections.singletonList(TerminalSession.InputRejectionReason.FULL), client.rejected);
            assertSame(session, client.rejectedSessions.get(0));
            session.mTerminalToProcessIOQueue.close();
            allowWrite.countDown();
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            // Release both waits even if the regression accidentally blocks admission under its lock.
            allowWrite.countDown();
            session.mTerminalToProcessIOQueue.close();
            session.cleanupResources(0);
            stop(workers);
        }
    }

    @Test public void closedQueueWakesIdleProductionWriterLoop() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<?> done = writer.submit(() -> { entered.countDown(); session.writeInputTo(output); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            session.cleanupResources(0);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(0, output.size());
            assertFalse(session.tryWrite("after close"));
            assertEquals(Collections.singletonList(TerminalSession.InputRejectionReason.CLOSED), client.rejected);
        } finally {
            session.mTerminalToProcessIOQueue.close();
            stop(writer);
        }
    }

    @Test public void writerFailureClosesAdmissionAndReportsWithoutNativeProcess() throws Exception {
        IOException original = new IOException("mock output failed");
        assertTrue(session.tryWrite("queued"));
        try {
            session.writeInputTo(new OutputStream() {
                @Override public void write(int value) throws IOException { throw original; }
            });
            fail("Expected the original output failure");
        } catch (IOException expected) {
            assertSame(original, expected);
        }
        assertFalse(session.tryWrite("later"));
        assertEquals(-1, session.mTerminalToProcessIOQueue.read(new byte[1], false));
        assertEquals(Collections.singletonList(TerminalSession.InputRejectionReason.CLOSED), client.rejected);
    }

    @Test public void neverStartedSessionDoesNotAdmitOrCloseDescriptorZero() {
        Client neverClient = new Client();
        TerminalSession never = fresh(neverClient);
        assertFalse(never.tryWrite("not started"));
        assertEquals(0, never.mTerminalToProcessIOQueue.read(new byte[1], false));
        never.cleanupResources(0);
        never.cleanupResources(0);
        assertTrue(NoPty.closed.isEmpty());
        assertEquals(0, NoPty.createCalls);
        assertEquals(Collections.singletonList(TerminalSession.InputRejectionReason.CLOSED), neverClient.rejected);
    }

    @Test public void nativeInitializationFailureClosesBothQueuesAndPreservesOriginalException() {
        TerminalSession failed = fresh(client);
        RuntimeException original = NoPty.createFailure;
        try {
            failed.initializeEmulator(80, 24, 8, 16);
            fail("Expected initialization failure");
        } catch (RuntimeException expected) {
            assertSame(original, expected);
        }
        assertFalse(failed.isRunning());
        assertFalse(failed.tryWrite("after failed initialization"));
        assertEquals(-1, failed.mTerminalToProcessIOQueue.read(new byte[1], false));
        assertEquals(-1, failed.mProcessToTerminalIOQueue.read(new byte[1], false));
        assertTrue(NoPty.closed.isEmpty());
        assertTrue(NoPty.waitedPids.isEmpty());
    }

    @Test public void invalidReturnedPidClosesOnlyReturnedDescriptorOnce() {
        NoPty.createFailure = null;
        NoPty.returnedDescriptor = 24;
        NoPty.returnedPid = 0;
        TerminalSession failed = fresh(client);
        try {
            failed.initializeEmulator(80, 24, 8, 16);
            fail("Expected invalid PID failure");
        } catch (IllegalStateException expected) {
            assertEquals("Terminal subprocess did not start", expected.getMessage());
        }
        failed.cleanupResources(0);
        assertEquals(Collections.singletonList(24), NoPty.closed);
        assertEquals(-1, (int) ReflectionHelpers.<Integer>getField(failed, "mTerminalFileDescriptor"));
        assertTrue(NoSignals.killedPids.isEmpty());
    }

    /** Bridge adoption only; the existing PFD shadow closes a real host RandomAccessFile. */
    @Implements(ParcelFileDescriptor.class)
    public static class OpenFileAdoption extends ShadowParcelFileDescriptor {
        private static ParcelFileDescriptor owner;

        @Implementation protected static ParcelFileDescriptor adoptFd(int descriptor) {
            assertEquals(owner.getFd(), descriptor);
            return owner;
        }
    }

    @Config(shadows = OpenFileAdoption.class)
    @Test public void adoptedDescriptorCleanupInvalidatesTheBorrowedJavaDescriptor() throws Exception {
        File file = File.createTempFile("terminal-pty", ".test");
        try (ParcelFileDescriptor owner = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)) {
            // API 28's native Linux.close is not implemented by Robolectric. A fake adopted
            // integer has no backing file; use its supported file owner without mocking close.
            OpenFileAdoption.owner = owner;
            int descriptor = owner.getFd();
            TerminalPty pty = new TerminalPty(descriptor);
            java.io.FileDescriptor borrowed = pty.getFileDescriptor();
            assertSame(owner.getFileDescriptor(), borrowed);
            assertTrue(borrowed.valid());
            ReflectionHelpers.setField(session, "mPty", pty);
            ReflectionHelpers.setField(session, "mTerminalFileDescriptor", descriptor);
            session.cleanupResources(0);
            session.cleanupResources(0);
            assertFalse(borrowed.valid());
            assertTrue("Adopted descriptors must not also use raw JNI.close", NoPty.closed.isEmpty());
        } finally {
            OpenFileAdoption.owner = null;
            file.delete();
        }
    }

    @Test public void postNativeClientFailureRejectsReentrantInputAndHandsChildToExistingWaiter() throws Exception {
        NoPty.createFailure = null;
        NoPty.returnedDescriptor = 24;
        NoPty.returnedPid = 444;
        client.probeAtPid = true;
        client.pidFailure = new IllegalStateException("mock PID client failed");
        TerminalSession failed = fresh(client);
        try {
            failed.initializeEmulator(80, 24, 8, 16);
            fail("Expected client failure");
        } catch (RuntimeException expected) {
            assertSame(client.pidFailure, expected);
        }
        assertEquals(Boolean.FALSE, client.probeAccepted);
        assertEquals(Collections.singletonList(444), NoSignals.killedPids);
        assertEquals(Collections.singletonList(OsConstants.SIGKILL), NoSignals.signals);
        assertTrue(NoPty.waited.await(5, TimeUnit.SECONDS));
        NoPty.waiter.join(5000);
        assertFalse(NoPty.waiter.isAlive());
        assertEquals(Collections.singletonList(444), NoPty.waitedPids);
        assertEquals(Collections.singletonList(24), NoPty.closed);
        assertFalse(failed.isRunning());
        assertEquals(-1, failed.mTerminalToProcessIOQueue.read(new byte[1], false));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, client.finished);
    }

    @Test public void secondaryFailuresDoNotSkipAdmissionCleanupOrReplaceOriginal() throws Exception {
        NoPty.createFailure = null;
        NoPty.returnedDescriptor = 24;
        NoPty.returnedPid = 445;
        client.pidFailure = new IllegalStateException("mock PID client failed");
        RuntimeException killFailure = new SecurityException("mock signal denied");
        RuntimeException closeFailure = new IllegalStateException("mock native close failed");
        NoSignals.killFailure = killFailure;
        NoPty.closeFailure = closeFailure;
        TerminalSession failed = fresh(client);
        try {
            failed.initializeEmulator(80, 24, 8, 16);
            fail("Expected original client failure");
        } catch (RuntimeException expected) {
            assertSame(client.pidFailure, expected);
            assertArrayEquals(new Throwable[]{killFailure, closeFailure}, expected.getSuppressed());
        }
        assertEquals(Collections.singletonList(445), NoSignals.killedPids);
        assertEquals(Collections.singletonList(24), NoPty.closed); // Close was attempted, not a real-fd claim.
        assertEquals(-1, failed.mTerminalToProcessIOQueue.read(new byte[1], false));
        assertEquals(-1, failed.mProcessToTerminalIOQueue.read(new byte[1], false));
        assertTrue(NoPty.waited.await(5, TimeUnit.SECONDS));
        NoPty.waiter.join(5000);
        assertFalse(NoPty.waiter.isAlive());
        assertEquals(Collections.singletonList(445), NoPty.waitedPids);
    }

    @Test public void sessionQueuesAndRejectionsStayIsolated() {
        Client otherClient = new Client();
        TerminalSession other = fixture(otherClient);
        session.mEmulator.paste("first");
        other.writeCodePoint(false, 'B');
        session.cleanupResources(0);
        assertFalse(session.tryWrite("must not reach other session"));
        assertArrayEquals(new byte[]{'B'}, drain(other));
        assertTrue(otherClient.rejected.isEmpty());
        assertSame(session, client.rejectedSessions.get(0));
    }

    @Test public void releasePromiseProtectsAgainstEveryOrdinarySessionAdmissionPath() {
        TerminalSession.InputReservation held = session.tryWriteAndReserveRelease("D", 8);
        assertNotNull(held);
        assertArrayEquals(new byte[]{'D'}, drain(session));
        byte[] pending = new byte[TerminalSession.MAX_PENDING_INPUT_BYTES - 8];
        assertTrue(session.tryWrite(pending, 0, pending.length));
        assertFalse(session.tryWrite("string"));
        assertFalse(session.tryWriteCodePoint(false, 'x'));
        session.write("void");
        session.writeCodePoint(false, 'y');
        session.getEmulator().paste("paste");
        append(session, "\033[6n"); // A terminal-generated reply must also leave the promise intact.
        assertTrue(held.writeRelease("U"));
        byte[] expected = Arrays.copyOf(pending, pending.length + 1);
        expected[pending.length] = 'U';
        assertArrayEquals(expected, drain(session));
        assertFalse(held.writeRelease("duplicate"));
        held.cancel();
        held.cancel();
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(session.mTerminalToProcessIOQueue, "mReservedBytes"));
        assertTrue(session.tryWrite(new byte[TerminalSession.MAX_PENDING_INPUT_BYTES], 0, TerminalSession.MAX_PENDING_INPUT_BYTES));
    }

    @Test public void multipleProtocolStalePromisesArePrunedBeforeOrdinaryInputWithoutConcurrentModification() {
        TerminalSession.InputReservation first = session.tryWriteAndReserveRelease("A", 3);
        TerminalSession.InputReservation second = session.tryWriteCodePointAndReserveRelease(false, 'B', 4);
        assertNotNull(first);
        assertNotNull(second);
        assertArrayEquals(new byte[]{'A', 'B'}, drain(session));
        append(session, "\033[=3u");
        byte[] pending = new byte[TerminalSession.MAX_PENDING_INPUT_BYTES];
        assertTrue(session.tryWrite(pending, 0, pending.length));
        assertFalse(first.writeRelease("old"));
        assertFalse(second.writeRelease("old"));
        first.cancel();
        second.cancel();
        assertArrayEquals(pending, drain(session));
    }

    @Test public void promiseUsesItsOriginalSessionAndCloseReturnsHeldCapacityIdempotently() {
        TerminalSession.InputReservation held = session.tryWriteAndReserveRelease("A", 3);
        assertNotNull(held);
        Client otherClient = new Client();
        TerminalSession other = fixture(otherClient);
        assertTrue(other.tryWrite("B"));
        assertTrue(held.writeRelease("U"));
        assertArrayEquals(new byte[]{'A', 'U'}, drain(session));
        assertArrayEquals(new byte[]{'B'}, drain(other));
        TerminalSession.InputReservation closing = session.tryWriteAndReserveRelease("D", 3);
        assertNotNull(closing);
        session.cleanupResources(0);
        closing.cancel();
        closing.cancel();
        assertFalse(closing.writeRelease("U"));
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(session.mTerminalToProcessIOQueue, "mReservedBytes"));
    }

}
