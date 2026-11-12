package com.termux.app.shared;

import android.app.Application;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Shared-wrapper ownership; native completion is supplied explicitly, not an Android PTY test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 37}, application = Application.class)
public class TermuxSessionLifecycleTest {
    @Test
    public void cancellationWaitsForNativeCompletionAndCollectsFinalTranscriptOnce() {
        Fixture fixture = new Fixture();
        fixture.mark(TerminalSession.ShellState.STARTING);
        TerminalEmulator emulator = new TerminalEmulator(fixture.terminal, 80, 24, 8, 16, 100,
            fixture.client);
        ReflectionHelpers.setField(fixture.terminal, "mEmulator", emulator);
        append(emulator, "before");

        fixture.wrapper.killIfExecuting(RuntimeEnvironment.getApplication(), true);
        fixture.wrapper.finish();
        assertEquals(0, fixture.callbacks.get());
        assertEquals("", fixture.command.resultData.stdout.toString());

        append(emulator, "-final-tail");
        fixture.mark(TerminalSession.ShellState.EXITED);
        ReflectionHelpers.setField(fixture.terminal, "mShellExitStatus", 137);
        fixture.client.onSessionFinished(fixture.terminal);
        String output = fixture.command.resultData.stdout.toString();
        assertTrue(output.contains("before-final-tail"));
        assertEquals(Integer.valueOf(137), fixture.command.resultData.exitCode);
        assertEquals(1, fixture.callbacks.get());

        fixture.wrapper.finish();
        assertEquals(output, fixture.command.resultData.stdout.toString());
        assertEquals(1, fixture.callbacks.get());
    }

    @Test
    public void cancellationBeforeInitializationReturnsEmptyOutputNotLiteralNull() {
        Fixture fixture = new Fixture();
        fixture.wrapper.killIfExecuting(RuntimeEnvironment.getApplication(), true);
        assertEquals("", fixture.command.resultData.stdout.toString());
        assertEquals(Integer.valueOf(137), fixture.command.resultData.exitCode);
        assertEquals(1, fixture.callbacks.get());
        fixture.wrapper.finish();
        assertEquals(1, fixture.callbacks.get());
    }

    @Test
    public void failedCommandStillCompletesWhenTheTerminalFinishes() {
        Fixture fixture = new Fixture();
        fixture.command.setStateFailed(1, "already failed");
        fixture.mark(TerminalSession.ShellState.EXITED);
        fixture.wrapper.finish();
        fixture.wrapper.finish();
        assertEquals(1, fixture.callbacks.get());
        assertTrue(fixture.command.isStateFailed());
    }

    @Test
    public void suppressedCancellationDoesNotDeliverDuringOrAfterFinish() {
        Fixture fixture = new Fixture();
        fixture.wrapper.killIfExecuting(RuntimeEnvironment.getApplication(), false);
        fixture.wrapper.finish();
        assertEquals(0, fixture.callbacks.get());
        assertEquals("", fixture.command.resultData.stdout.toString());
        assertEquals(Integer.valueOf(137), fixture.command.resultData.exitCode);
        assertFalse(fixture.command.claimResultDelivery());
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class Fixture {
        final AtomicInteger callbacks = new AtomicInteger();
        final ExecutionCommand command = new ExecutionCommand(1, "/unused", new String[0], null,
            "/", ExecutionCommand.Runner.TERMINAL_SESSION.getName(), false);
        final TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase() {
            @Override public void onSessionFinished(TerminalSession session) {
                wrapper.finish();
            }
        };
        final TerminalSession terminal = new TerminalSession("/unused", "/", new String[0],
            new String[0], 100, client);
        final TermuxSession wrapper;

        Fixture() {
            command.setState(ExecutionCommand.ExecutionState.EXECUTING);
            wrapper = ReflectionHelpers.callConstructor(TermuxSession.class,
                ClassParameter.from(TerminalSession.class, terminal),
                ClassParameter.from(ExecutionCommand.class, command),
                ClassParameter.from(TermuxSession.TermuxSessionClient.class,
                    completed -> callbacks.incrementAndGet()),
                ClassParameter.from(boolean.class, true));
        }

        void mark(TerminalSession.ShellState state) {
            ReflectionHelpers.setField(terminal, "mShellState", state);
        }
    }
}
