package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Actual InputConnection -> emulator paste -> session admission; no real PTY. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class, shadows = TerminalImePasteTest.NoPty.class)
public class TerminalImeAdmissionTest {
    private byte[] drain(Object queue) {
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        while (true) {
            int count = ReflectionHelpers.callInstanceMethod(queue, "read",
                ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
            if (count <= 0) return accepted.toByteArray();
            accepted.write(bytes, 0, count);
        }
    }

    @Test public void bracketedMultilineCommitRejectsWholeFrameThenRecovers() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        int[] rejected = {0};
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase() {
            @Override public void onInputRejected(TerminalSession session, TerminalSession.InputRejectionReason reason) {
                assertEquals(TerminalSession.InputRejectionReason.FULL, reason);
                rejected[0]++;
            }
        };
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, client));
        TestSessions.markShell(session, 1);
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        try {
            TerminalView terminal = new TerminalView(controller.get(), null);
            terminal.setTerminalViewClient(new TermuxTerminalViewClientBase() {
                @Override public boolean shouldEnforceCharBasedInput() { return true; }
            });
            terminal.setTextSize(16);
            controller.get().setContentView(terminal);
            terminal.attachSession(session);
            terminal.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
            terminal.layout(0, 0, 900, 1000);
            InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
            assertNotNull(input);
            byte[] mode = "\033[?2004h".getBytes(StandardCharsets.UTF_8);
            session.getEmulator().append(mode, mode.length);
            int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
            byte[] pending = new byte[capacity - 8];
            Arrays.fill(pending, (byte) 'P');
            assertTrue(session.tryWrite(pending, 0, pending.length));

            // IME's return value acknowledges its edit, not FIFO admission. The callback reports
            // the rejection; no prefix/body/suffix is admitted even though a prefix would fit.
            assertTrue(input.commitText("ok\nz", 1));
            assertArrayEquals(pending, drain(queue));
            assertEquals(1, rejected[0]);
            assertTrue(input.commitText("ok\nz", 1));
            assertArrayEquals("\033[200~ok\rz\033[201~".getBytes(StandardCharsets.UTF_8), drain(queue));
            assertEquals(1, rejected[0]);
        } finally {
            ReflectionHelpers.callInstanceMethod(queue, "close");
            controller.pause().stop().destroy();
        }
    }
}
