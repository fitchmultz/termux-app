package com.termux.app;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;

import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.shared.shell.command.ExecutionCommand;
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

import java.lang.reflect.Constructor;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TermuxServiceCompletionTest {
    private TermuxService service;
    private TermuxShellManager manager;
    private ExecutionCommand command;
    private TerminalSession terminal;
    private TermuxSession session;

    @Before public void setUp() throws Exception {
        service = Robolectric.buildService(TermuxService.class).get();
        manager = new TermuxShellManager(service);
        ReflectionHelpers.setField(service, "mShellManager", manager);
        command = new ExecutionCommand(1);
        command.setState(ExecutionCommand.ExecutionState.EXECUTING);
        terminal = new TerminalSession("/unused", "/", new String[0], new String[0], 100,
            service.getTermuxTerminalSessionClient());
        // Build the real wrapper without starting a native Android PTY on the host.
        Constructor<TermuxSession> constructor = TermuxSession.class.getDeclaredConstructor(
            TerminalSession.class, ExecutionCommand.class, TermuxSession.TermuxSessionClient.class, boolean.class);
        constructor.setAccessible(true);
        session = constructor.newInstance(terminal, command, service, false);
        manager.trackSession(session);
    }

    private void exited(int status) {
        ReflectionHelpers.setField(terminal, "mShellState", TerminalSession.ShellState.EXITED);
        ReflectionHelpers.setField(terminal, "mShellExitStatus", status);
        service.getTermuxTerminalSessionClient().onSessionFinished(terminal);
    }

    @Test public void detachedPluginCompletionDeliversOneResultAndRemovesSession() {
        command.isPluginExecutionCommand = true;
        command.resultConfig.resultPendingIntent = PendingIntent.getBroadcast(service, 1,
            new Intent("test.plugin.result"), 0);
        service.unsetTermuxTerminalSessionClient();
        int before = Shadows.shadowOf((Application) service.getApplication()).getBroadcastIntents().size();
        exited(7);
        assertTrue(manager.mTermuxSessions.isEmpty());
        assertEquals(7, command.resultData.exitCode.intValue());
        assertEquals(before + 1, Shadows.shadowOf((Application) service.getApplication()).getBroadcastIntents().size());
        service.getTermuxTerminalSessionClient().onSessionFinished(terminal);
        service.onTermuxSessionExited(session);
        assertEquals(before + 1, Shadows.shadowOf((Application) service.getApplication()).getBroadcastIntents().size());
    }

    @Test public void requestedKillSurvivesActivityClientReplacement() {
        // STARTING has no PID to signal yet, so completion can arrive after rebinding.
        ReflectionHelpers.setField(terminal, "mShellState", TerminalSession.ShellState.STARTING);
        service.setTermuxTerminalSessionClient(new TermuxTerminalSessionActivityClient(new TermuxActivity()));
        service.requestSessionRemoval(terminal);
        service.unsetTermuxTerminalSessionClient();
        service.setTermuxTerminalSessionClient(new TermuxTerminalSessionActivityClient(new TermuxActivity()));
        service.unsetTermuxTerminalSessionClient();
        exited(-9);
        assertTrue(manager.mTermuxSessions.isEmpty());
    }

    @Test public void unexpectedFailureStaysUntilExplicitRemoval() {
        exited(-9);
        assertSame(session, service.getTermuxSession(0));
        service.requestSessionRemoval(terminal);
        assertTrue(manager.mTermuxSessions.isEmpty());
    }

    @Test public void unstartedRequestedExitCanCompleteReentrantly() {
        service.requestSessionRemoval(terminal);
        assertTrue(manager.mTermuxSessions.isEmpty());
        assertEquals(137, terminal.getExitStatus());
    }
}
