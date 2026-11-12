package com.termux.app.terminal;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Actual session callback to visible Activity toast, with fake sessions and no native PTY. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = EvidenceDockTest.TestApplication.class,
    qualifiers = "w900dp-h1000dp-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalInputRejectionTest {
    private TerminalSession session(String name, TermuxShellManager manager) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession terminal = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(terminal, "mEmulator", new TerminalEmulator(terminal, 80, 24, 8, 16, 100, client));
        TestSessions.markShell(terminal, Integer.MAX_VALUE);
        terminal.mSessionName = name;
        ExecutionCommand command = new ExecutionCommand(1, "/unused", new String[0], null, "/",
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(), true);
        manager.mTermuxSessions.add(ReflectionHelpers.callConstructor(TermuxSession.class,
            ClassParameter.from(TerminalSession.class, terminal),
            ClassParameter.from(ExecutionCommand.class, command),
            ClassParameter.from(TermuxSession.TermuxSessionClient.class, null),
            ClassParameter.from(boolean.class, false)));
        return terminal;
    }

    private ActivityController<TermuxActivity> start() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxAppSharedProperties.init(app);
        TermuxService service = Robolectric.buildService(TermuxService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            new ComponentName(app, TermuxService.class), service.onBind(new Intent(app, TermuxService.class)));
        return Robolectric.buildActivity(TermuxActivity.class).setup();
    }

    @Test public void fullAndClosedRejectionsIdentifyOriginalSessionWithoutPayloadOrRerouting() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("one", manager), second = session("two", manager);
        ActivityController<TermuxActivity> controller = start();
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            TermuxActivity activity = controller.get();
            TermuxTerminalSessionActivityClient ui = new TermuxTerminalSessionActivityClient(activity);
            ui.setCurrentSession(second);
            first.updateTerminalSessionClient(ui);
            TerminalPaneLayoutTest.size(activity.findViewById(R.id.activity_termux_root_view), 900, 650);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertSame(second, activity.getCurrentSession());
            int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
            byte[] pending = new byte[capacity];
            assertTrue(first.tryWrite(pending, 0, pending.length));
            ShadowToast.reset();
            assertFalse(producer.submit(() -> first.tryWrite("PRIVATE_REJECTED_TEXT")).get(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String full = String.valueOf(ShadowToast.getTextOfLatestToast());
            assertTrue(full.contains(activity.getString(R.string.terminal_input_rejected_full)));
            assertTrue(full.contains("[1] one"));
            assertFalse(full.contains("PRIVATE_REJECTED_TEXT"));
            int count = ShadowToast.shownToastCount();
            assertFalse(first.tryWrite("another rejected event"));
            assertEquals(count, ShadowToast.shownToastCount());

            Object queue = ReflectionHelpers.getField(first, "mTerminalToProcessIOQueue");
            ReflectionHelpers.callInstanceMethod(queue, "close");
            assertFalse(first.tryWrite("after close"));
            String closed = String.valueOf(ShadowToast.getTextOfLatestToast());
            assertTrue(closed.contains(activity.getString(R.string.terminal_input_rejected_closed)));
            assertTrue(closed.contains("[1] one"));
            assertSame(second, activity.getCurrentSession());
            assertTrue(second.tryWrite("B"));
            Object otherQueue = ReflectionHelpers.getField(second, "mTerminalToProcessIOQueue");
            byte[] output = new byte[128];
            int read = ReflectionHelpers.callInstanceMethod(otherQueue, "read",
                ClassParameter.from(byte[].class, output), ClassParameter.from(boolean.class, false));
            assertEquals("B", new String(output, 0, read, StandardCharsets.UTF_8));
        } finally {
            Object queue = ReflectionHelpers.getField(first, "mTerminalToProcessIOQueue");
            ReflectionHelpers.callInstanceMethod(queue, "close");
            producer.shutdownNow();
            assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
            controller.pause().stop().destroy();
        }
    }
}
