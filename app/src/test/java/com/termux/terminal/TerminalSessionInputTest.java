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

import java.nio.charset.StandardCharsets;

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

    @Before public void setUp() {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase() {
            @Override public void onTextChanged(TerminalSession changedSession) {
                assertSame(session, changedSession);
                updates++;
                if (updates == 1 && firstUpdate != null) firstUpdate.run();
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
}
