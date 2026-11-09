package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;

/**
 * Records actual session input-FIFO bytes for a separate, unchanged Pi Editor consumer probe.
 * Events are synthetic Android framework events, not physical HID; no native PTY is started.
 * Existing tests are neither inherited nor changed. Every recorded byte comes from the real queue,
 * while expected-byte constants are used only for assertions after reading that queue.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class, shadows = TerminalImePasteTest.NoPty.class)
public class PiKeyboardWorkflowCaptureTest {
    private static final String ANDROID_SOURCE_BASE = "00c0896ec49aaf4e3fc62ab41bc71cbff3c75440";

    private static final class Fixture {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        final TerminalSession session;
        final TerminalView terminal;
        final InputConnection input;
        final Object queue;

        Fixture() {
            TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
            session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
            ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, client));
            // Same no-child fixture boundary as TerminalImePasteTest: real FIFO, no subprocess.
            ReflectionHelpers.setField(session, "mShellPid", 1234);
            queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
            terminal = new TerminalView(controller.get(), null);
            terminal.setTerminalViewClient(new TermuxTerminalViewClientBase() {
                @Override public boolean shouldEnforceCharBasedInput() { return true; }
            });
            terminal.setTextSize(16);
            terminal.setFocusableInTouchMode(true);
            controller.get().setContentView(terminal);
            terminal.attachSession(session);
            terminal.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
            terminal.layout(0, 0, 900, 1000);
            assertTrue(terminal.requestFocus());
            input = terminal.onCreateInputConnection(new EditorInfo());
            assertNotNull(input);
        }

        byte[] drain() {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            while (true) {
                int count = ReflectionHelpers.callInstanceMethod(queue, "read",
                    ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
                if (count <= 0) return output.toByteArray();
                output.write(bytes, 0, count);
            }
        }

        void close() {
            try {
                input.closeConnection();
            } finally {
                ReflectionHelpers.callInstanceMethod(queue, "close");
                controller.pause().stop().destroy();
            }
        }
    }

    private static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            result.append(digits[unsigned >>> 4]).append(digits[unsigned & 15]);
        }
        return result.toString();
    }

    private void captureEvent(Fixture fixture, JSONArray records, String id, KeyEvent event,
                              String expected) throws Exception {
        assertTrue(event.getAction() == KeyEvent.ACTION_DOWN
            ? fixture.terminal.onKeyDown(event.getKeyCode(), event)
            : fixture.terminal.onKeyUp(event.getKeyCode(), event));
        byte[] actual = fixture.drain();
        assertArrayEquals(id, expected.getBytes(StandardCharsets.UTF_8), actual);
        records.put(new JSONObject()
            .put("id", id)
            .put("inputKind", "physical")
            .put("eventType", event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up")
            .put("keyCode", event.getKeyCode())
            .put("metaState", event.getMetaState())
            .put("repeatCount", event.getRepeatCount())
            .put("bytesHex", hex(actual)));
    }

    private void captureKey(Fixture fixture, JSONArray records, String id, int keyCode, int modifiers,
                            long downTime, String expectedDown, String expectedUp) throws Exception {
        captureEvent(fixture, records, id, new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0), expectedDown);
        // Keep releases as separate records. Direct Editor.handleInput has no TUI release filter.
        captureEvent(fixture, records, id + "-up", new KeyEvent(downTime, downTime + 1, KeyEvent.ACTION_UP,
            keyCode, 0, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0), expectedUp);
    }

    @Test public void captureActualAndroidBytesForUnchangedPiEditorWorkflow() throws Exception {
        Fixture fixture = new Fixture();
        try {
            byte[] negotiation = "\033[>7u".getBytes(StandardCharsets.UTF_8);
            fixture.session.getEmulator().append(negotiation, negotiation.length);
            assertEquals(7, fixture.session.getEmulator().getKittyKeyboardFlags());
            assertArrayEquals(new byte[0], fixture.drain());

            JSONArray records = new JSONArray();
            captureKey(fixture, records, "a1", KeyEvent.KEYCODE_A, 0, 100, "a", "");
            captureKey(fixture, records, "shift-enter", KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON,
                200, "\033[13;2u", "");
            captureKey(fixture, records, "b1", KeyEvent.KEYCODE_B, 0, 300, "b", "");
            captureKey(fixture, records, "ctrl-j", KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON,
                400, "\033[106;5u", "\033[106;5:3u");
            captureKey(fixture, records, "a2", KeyEvent.KEYCODE_A, 0, 500, "a", "");
            captureKey(fixture, records, "enter", KeyEvent.KEYCODE_ENTER, 0, 600, "\r", "");
            captureKey(fixture, records, "b2", KeyEvent.KEYCODE_B, 0, 700, "b", "");

            assertTrue(fixture.input.commitText("\n", 1));
            byte[] literalImeEnter = fixture.drain();
            assertArrayEquals("IME newline is literal CR, not an inferred Kitty Ctrl+M",
                "\r".getBytes(StandardCharsets.UTF_8), literalImeEnter);
            records.put(new JSONObject()
                .put("id", "ime-newline")
                .put("inputKind", "ime")
                .put("eventType", "commitText")
                .put("text", "\n")
                .put("newCursorPosition", 1)
                .put("bytesHex", hex(literalImeEnter)));

            int sdk = RuntimeEnvironment.getApiLevel();
            JSONObject artifact = new JSONObject()
                .put("schema", "fold.pi-input-proof.v1")
                .put("androidSourceBase", ANDROID_SOURCE_BASE)
                .put("sdk", sdk)
                .put("kittyFlags", fixture.session.getEmulator().getKittyKeyboardFlags())
                .put("records", records);
            Path directory = Paths.get("build", "reports", "tests", "workbench");
            Files.createDirectories(directory);
            // Unique filenames retain both SDKs and repeated Debug/Release runs without overwrite.
            Path output = Files.createTempFile(directory, "pi-input-sdk-" + sdk + "-", ".json");
            Files.write(output, (artifact.toString(2) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
            System.out.println("PI_INPUT_PROOF_JSON=" + output.toAbsolutePath());
        } finally {
            fixture.close();
        }
    }
}
