package com.termux.app.shared;

import android.app.Application;
import android.content.Context;

import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises real host child processes, not Android's process implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 37}, application = Application.class)
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
}
