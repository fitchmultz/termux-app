package com.termux.app.terminal;

import com.termux.terminal.TerminalSession;

import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;

/**
 * Helpers for tests that attach a {@link TerminalSession} to app views without starting a shell.
 *
 * <p>A session's shell lifecycle is one value, so a fixture that fakes a live or finished shell must
 * set the pid and the state together. Otherwise the session looks created-but-never-started and
 * rejects input that the test expects to be written.
 */
final class TestSessions {

    private TestSessions() { }

    /** Mark the session as having a shell at {@code pid}; a negative pid means it already exited. */
    static void markShell(TerminalSession session, int pid) {
        ReflectionHelpers.setField(session, "mShellPid", pid);
        ReflectionHelpers.setField(session, "mShellState",
            pid > 0 ? TerminalSession.ShellState.RUNNING : TerminalSession.ShellState.EXITED);
    }
}
