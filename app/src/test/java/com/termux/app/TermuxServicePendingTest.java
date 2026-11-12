package com.termux.app;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants.TERMUX_SERVICE;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.lang.reflect.Constructor;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TermuxServicePendingTest {
    private TermuxService service;
    private TermuxShellManager manager;

    @Before public void setUp() {
        service = Robolectric.buildService(TermuxService.class).get();
        manager = new TermuxShellManager(service);
        ReflectionHelpers.setField(service, "mShellManager", manager);
        ReflectionHelpers.setField(service, "mProperties", TermuxAppSharedProperties.init(service));
    }

    private PendingIntent resultIntent() {
        return PendingIntent.getBroadcast(service, 1, new Intent("test.pending.result"), 0);
    }

    private int results() {
        return Shadows.shadowOf((Application) service.getApplication()).getBroadcastIntents().size();
    }

    private Intent request(ExecutionCommand.Runner runner) {
        return new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
            .putExtra(TERMUX_SERVICE.EXTRA_RUNNER, runner.getName())
            .putExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT, resultIntent());
    }

    private void execute(Intent intent) {
        ReflectionHelpers.callInstanceMethod(service, "actionServiceExecute", ClassParameter.from(Intent.class, intent));
    }

    private void shutdown() {
        ReflectionHelpers.callInstanceMethod(service, "killAllTermuxExecutionCommands");
    }

    @Test public void rejectedCreateModesRetireBothRunnersBeforeShutdown() {
        for (ExecutionCommand.Runner runner : ExecutionCommand.Runner.values()) {
            int before = results();
            execute(request(runner).putExtra(TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, "invalid"));
            assertTrue(manager.mPendingPluginExecutionCommands.isEmpty());
            assertEquals(before + 1, results());
            shutdown();
            assertEquals(before + 1, results());
        }
    }

    @Test public void missingRequiredNameRetiresRequest() {
        execute(request(ExecutionCommand.Runner.TERMINAL_SESSION)
            .putExtra(TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, ExecutionCommand.ShellCreateMode.NO_SHELL_WITH_NAME.getMode()));
        assertTrue(manager.mPendingPluginExecutionCommands.isEmpty());
        int delivered = results();
        assertEquals(1, delivered);
        shutdown();
        assertEquals(delivered, results());
    }

    @Test public void failedBackgroundStartIsNotCancelledAgain() {
        Intent intent = request(ExecutionCommand.Runner.APP_SHELL);
        intent.setData(Uri.parse("file:///missing-test-command"));
        execute(intent);
        assertTrue(manager.mTermuxTasks.isEmpty());
        assertTrue(manager.mPendingPluginExecutionCommands.isEmpty());
        int delivered = results();
        assertEquals(1, delivered);
        shutdown();
        assertEquals(delivered, results());
    }

    @Test public void namedSessionReuseRetiresOnlyTheNewRequest() throws Exception {
        ExecutionCommand original = new ExecutionCommand(100);
        original.shellName = "existing";
        TerminalSession terminal = new TerminalSession("/unused", "/", new String[0], new String[0], 100,
            service.getTermuxTerminalSessionClient());
        Constructor<TermuxSession> constructor = TermuxSession.class.getDeclaredConstructor(
            TerminalSession.class, ExecutionCommand.class, TermuxSession.TermuxSessionClient.class, boolean.class);
        constructor.setAccessible(true);
        TermuxSession existing = constructor.newInstance(terminal, original, service, false);
        manager.trackSession(existing);
        execute(request(ExecutionCommand.Runner.TERMINAL_SESSION)
            .putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, "existing")
            .putExtra(TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, ExecutionCommand.ShellCreateMode.NO_SHELL_WITH_NAME.getMode())
            .putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, Integer.toString(
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY)));
        assertTrue(manager.mPendingPluginExecutionCommands.isEmpty());
        assertEquals(1, manager.mTermuxSessions.size());
        assertSame(existing, manager.mTermuxSessions.get(0));
        assertSame(original, existing.getExecutionCommand());
        manager.untrackSession(existing);
        shutdown();
        assertEquals(0, results());
    }

    @Test public void pendingRetirementIsIdempotent() {
        ExecutionCommand pending = new ExecutionCommand(1);
        manager.trackPending(pending);
        assertTrue(manager.retirePending(pending));
        assertFalse(manager.retirePending(pending));
        assertTrue(manager.takePendingCommands().isEmpty());
    }

    @Test public void shutdownDrainsPendingOnceIncludingRequestsWithoutResults() {
        ExecutionCommand pending = new ExecutionCommand(1);
        pending.isPluginExecutionCommand = true;
        pending.resultConfig.resultPendingIntent = resultIntent();
        manager.trackPending(pending);
        manager.trackPending(new ExecutionCommand(2));
        shutdown();
        assertTrue(manager.mPendingPluginExecutionCommands.isEmpty());
        assertEquals(1, results());
        shutdown();
        assertTrue(manager.mPendingPluginExecutionCommands.isEmpty());
        assertEquals(1, results());
    }
}
