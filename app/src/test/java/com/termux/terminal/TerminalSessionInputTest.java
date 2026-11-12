package com.termux.terminal;

import android.app.Application;
import android.os.Looper;
import android.os.Message;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises the production queue and Handler, without starting a native subprocess. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalSessionInputTest {
    private TerminalSession session;
    private int updates;
    private Runnable firstUpdate;
    private int newInputMessage;
    private int finished;
    private String finishedText;

    @Before public void setUp() {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase() {
            @Override public void onTextChanged(TerminalSession changedSession) {
                assertSame(session, changedSession);
                updates++;
                if (updates == 1 && firstUpdate != null) firstUpdate.run();
            }
            @Override public void onSessionFinished(TerminalSession changedSession) {
                finished++;
                finishedText = text();
            }
        };
        session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        session.mEmulator = new TerminalEmulator(session, 200, 4, 8, 16, 100, client);
        newInputMessage = ReflectionHelpers.getStaticField(TerminalSession.class, "MSG_NEW_INPUT");
    }

    private void enqueue(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(session.mProcessToTerminalIOQueue.write(bytes, 0, bytes.length));
        session.scheduleInputProcessing();
    }

    private void runOneMessage() {
        Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
    }

    private String text() {
        return session.mEmulator.getScreen().getTranscriptText();
    }

    @Test public void repeatedReaderWakeupsProduceOneQueuedDrainAndPermitLaterInput() {
        for (int i = 0; i < 100; i++) enqueue("a");
        assertTrue(session.mMainThreadHandler.hasMessages(newInputMessage));
        runOneMessage();
        // There must not be 99 empty wakeups remaining behind the useful one.
        assertFalse(session.mMainThreadHandler.hasMessages(newInputMessage));
        assertEquals(1, updates);
        assertEquals("a".repeat(100), text());

        enqueue("b");
        runOneMessage();
        assertEquals(2, updates);
        assertEquals("a".repeat(100) + "b", text());
    }

    @Test public void inputArrivingAfterDrainSchedulesAnotherMessageRatherThanLosingWakeup() {
        // onTextChanged runs after the handler has drained the queue. Enqueueing there exercises
        // the interleaving in which a producer writes before the current handler returns.
        firstUpdate = () -> enqueue("b");
        enqueue("a");
        runOneMessage();
        assertEquals("a", text());
        assertTrue(session.mMainThreadHandler.hasMessages(newInputMessage));
        runOneMessage();
        assertEquals("ab", text());
        assertEquals(2, updates);
        assertFalse(session.mMainThreadHandler.hasMessages(newInputMessage));
    }

    @Test public void synchronizedTimeoutDoesNotDrainQueuedInputOrLoseItsWakeup() {
        byte[] frame = "\033[?2026hP".getBytes(StandardCharsets.UTF_8);
        session.mEmulator.append(frame, frame.length);
        session.notifyScreenUpdate();
        assertTrue(session.mEmulator.isSynchronizedOutputActive());
        assertEquals(0, updates);

        enqueue("Q");
        int timeout = ReflectionHelpers.getStaticField(TerminalSession.class, "MSG_SYNCHRONIZED_OUTPUT_TIMEOUT");
        session.mMainThreadHandler.dispatchMessage(Message.obtain(session.mMainThreadHandler, timeout));
        assertFalse(session.mEmulator.isSynchronizedOutputActive());
        assertEquals("P", text());
        assertTrue(session.mMainThreadHandler.hasMessages(newInputMessage));
        assertEquals(1, updates);

        runOneMessage();
        assertEquals("PQ", text());
        assertEquals(2, updates);
        assertFalse(session.mMainThreadHandler.hasMessages(newInputMessage));
    }

    @Test public void nativeExitWaitsOffMainForReaderPublicationBeforeFinishedCallback() throws Exception {
        CountDownLatch releaseTail = new CountDownLatch(1);
        CountDownLatch readerReady = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                enqueue("first ");
                readerReady.countDown();
                assertTrue(releaseTail.await(5, TimeUnit.SECONDS));
                enqueue("last output");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        ReflectionHelpers.setField(session, "mInputReader", reader);
        ReflectionHelpers.setField(session, "mShellState", TerminalSession.ShellState.RUNNING);
        session.mShellPid = 1234;
        reader.start();
        assertTrue(readerReady.await(5, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> {
            try {
                // Deliver the real native-waiter's result at its Java ownership boundary.
                ReflectionHelpers.callInstanceMethod(session, "onProcessExit", ClassParameter.from(int.class, 7));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        waiter.start();
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("first", text());
            assertEquals(0, finished);
            assertTrue("Main may drain output while the waiter is still waiting", waiter.isAlive());
        } finally {
            releaseTail.countDown();
            reader.join(5000);
            waiter.join(5000);
        }
        assertNull(failure.get());
        assertFalse(reader.isAlive());
        assertFalse(waiter.isAlive());
        assertEquals(-1, session.getPid());
        assertFalse(session.tryWrite("input after waitpid"));
        assertTrue("Session completion still awaits its main-thread final drain", session.isRunning());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, finished);
        assertTrue(finishedText, finishedText.contains("first last output"));
        assertEquals(7, session.getExitStatus());
        assertFalse(session.isRunning());
    }
}
