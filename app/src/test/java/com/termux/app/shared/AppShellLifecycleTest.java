package com.termux.app.shared;

import android.app.Application;
import android.content.Context;
import android.system.Os;

import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Exercises real host child processes, not Android's process implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 37}, application = Application.class, shadows = {
    AppShellLifecycleTest.HostProcessIds.class, AppShellLifecycleTest.HostSignals.class
})
public class AppShellLifecycleTest {
    @Test
    public void missingAndEmptyInputReachEofBeforeWaitingForExit() throws Exception {
        assertInputReachesEof(null, "");
        assertInputReachesEof("", "");
    }

    @Test
    public void suppliedInputIsDrainedBeforeEof() throws Exception {
        assertInputReachesEof("hello", "hello\n");
    }

    @Test
    public void cancellationPublishesOnlyAfterChildAndReadersFinish() throws Exception {
        ExecutionCommand command = new ExecutionCommand(1, "/bin/sh",
            new String[]{"-c", "while :; do printf 'output\\n'; done"},
            null, "/", ExecutionCommand.Runner.APP_SHELL.getName(), false);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Boolean> aliveAtCallback = new AtomicReference<>();
        AtomicReference<String> outputAtCallback = new AtomicReference<>();
        AppShell shell = AppShell.execute(RuntimeEnvironment.getApplication(), command, completed -> {
            aliveAtCallback.set(completed.getProcess().isAlive());
            outputAtCallback.set(completed.getExecutionCommand().resultData.stdout.toString());
            callbacks.incrementAndGet();
            exited.countDown();
        }, HOST_ENVIRONMENT, null, false);
        assertNotNull(shell);
        try {
            shell.killIfExecuting(RuntimeEnvironment.getApplication(), true);
            assertThrows(IOException.class, () -> shell.getProcess().getInputStream().read());
            assertThrows(IOException.class, () -> shell.getProcess().getErrorStream().read());
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertFalse(aliveAtCallback.get());
            assertEquals(Integer.valueOf(137), command.resultData.exitCode);
            assertEquals(outputAtCallback.get(), command.resultData.stdout.toString());
            shell.killIfExecuting(RuntimeEnvironment.getApplication(), true);
            assertEquals(1, callbacks.get());
        } finally {
            shell.getProcess().destroyForcibly();
        }
    }

    private void assertInputReachesEof(String input, String expected) throws Exception {
        ExecutionCommand command = new ExecutionCommand(1, "/bin/sh",
            new String[]{"-c", "while IFS= read -r line; do printf '%s\\n' \"$line\"; done"},
            input, "/", ExecutionCommand.Runner.APP_SHELL.getName(), false);
        CountDownLatch exited = new CountDownLatch(1);
        AppShell shell = AppShell.execute(RuntimeEnvironment.getApplication(), command,
            completed -> exited.countDown(), HOST_ENVIRONMENT, null, false);
        assertNotNull(shell);
        try {
            assertTrue("Child is waiting for stdin EOF", exited.await(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(0), command.resultData.exitCode);
            assertEquals(expected, command.resultData.stdout.toString());
        } finally {
            shell.getProcess().destroy();
        }
    }

    private static final IShellEnvironment HOST_ENVIRONMENT = new IShellEnvironment() {
        @Override public String getDefaultWorkingDirectoryPath() { return "/"; }
        @Override public String getDefaultBinPath() { return "/bin"; }
        @Override public String[] setupShellCommandArguments(String executable, String[] arguments) {
            return ShellUtils.setupShellCommandArguments(executable, arguments);
        }
        @Override public HashMap<String, String> setupShellCommandEnvironment(
            Context context, ExecutionCommand command) {
            return new HashMap<>();
        }
    };

    /** Bridge only Android signal/PID operations; child execution and pipe readers stay real. */
    @Implements(value = ShellUtils.class, isInAndroidSdk = false)
    public static class HostProcessIds {
        private static final Map<Integer, Process> processes = new ConcurrentHashMap<>();

        @Implementation
        protected static int getPid(Process process) {
            int pid = Math.toIntExact(process.pid());
            processes.put(pid, process);
            return pid;
        }
    }

    @Implements(Os.class)
    public static class HostSignals {
        @Implementation
        protected static void kill(int pid, int signal) {
            Process process = HostProcessIds.processes.get(pid);
            if (process != null) process.destroyForcibly();
        }
    }
}
