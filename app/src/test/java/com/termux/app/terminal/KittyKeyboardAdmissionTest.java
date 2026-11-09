package com.termux.app.terminal;

import android.app.Application;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Real TerminalView key-event paths and FIFO receipts, without native PTY or a physical keyboard. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class)
public class KittyKeyboardAdmissionTest {
    private TerminalView view;
    private TerminalSession session;
    private Object queue;
    private final List<TerminalSession.InputRejectionReason> rejections = new ArrayList<>();
    private int codePointCalls;
    private boolean consumeText;

    @Before public void setUp() {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase() {
            @Override public void onInputRejected(TerminalSession s, TerminalSession.InputRejectionReason reason) {
                assertSame(session, s);
                rejections.add(reason);
            }
        };
        session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        TerminalEmulator emulator = new TerminalEmulator(session, 20, 4, 8, 16, 100, client);
        ReflectionHelpers.setField(session, "mEmulator", emulator);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override public boolean onCodePoint(int codePoint, boolean controlDown, TerminalSession s) {
                codePointCalls++;
                return consumeText;
            }
        });
        ReflectionHelpers.setField(view, "mTermSession", session);
        view.mEmulator = emulator;
    }

    @After public void tearDown() {
        ReflectionHelpers.callInstanceMethod(queue, "close");
    }

    private void flags(int value) {
        byte[] mode = ("\033[=" + value + "u").getBytes(StandardCharsets.UTF_8);
        view.mEmulator.append(mode, mode.length);
    }

    private byte[] drain() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        while (true) {
            int count = ReflectionHelpers.callInstanceMethod(queue, "read",
                ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
            if (count <= 0) return output.toByteArray();
            output.write(bytes, 0, count);
        }
    }

    private byte[] fill() {
        int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
        byte[] pending = new byte[capacity];
        Arrays.fill(pending, (byte) 'P');
        assertTrue(session.tryWrite(pending, 0, pending.length));
        return pending;
    }

    private void key(int action, int code, int repeat, int modifiers) {
        KeyEvent event = new KeyEvent(10, 10 + repeat + (action == KeyEvent.ACTION_UP ? 2 : 0),
            action, code, repeat, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
        assertTrue("Recognized keys remain consumed even when admission fails",
            action == KeyEvent.ACTION_UP ? view.onKeyUp(code, event) : view.onKeyDown(code, event));
    }

    private void expect(String bytes) {
        assertArrayEquals(bytes.getBytes(StandardCharsets.UTF_8), drain());
    }

    @Test public void rejectedFunctionalPressCannotProduceOrphanReleaseAfterDrain() {
        flags(3);
        byte[] pending = fill();
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        assertArrayEquals(pending, drain());
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("");
        assertEquals(0, codePointCalls); // A rejected functional event did not retry as text.
        assertEquals(Arrays.asList(TerminalSession.InputRejectionReason.FULL), rejections);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[1;1:3A");
    }

    @Test public void rejectedEncodedCtrlLetterCannotProduceOrphanReleaseOrLegacyRetry() {
        for (int mode : new int[]{3, 7}) {
            flags(mode);
            byte[] pending = fill();
            int callsBefore = codePointCalls, rejectedBefore = rejections.size();
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            assertEquals(callsBefore + 1, codePointCalls);
            assertArrayEquals(pending, drain());
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            expect("");
            assertEquals(rejectedBefore + 1, rejections.size());
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            expect("\033[105;5u");
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            expect("\033[105;5:3u");
        }
    }

    @Test public void flag2LegacyCtrlITracksReleaseOnlyAfterActualCodePointAdmission() {
        flags(2); // The press is legacy TAB, but its accepted release still has Kitty identity i.
        byte[] pending = fill();
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        assertArrayEquals(pending, drain());
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        expect("");
        assertEquals(1, codePointCalls);
        assertEquals(Arrays.asList(TerminalSession.InputRejectionReason.FULL), rejections);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        expect("\t");
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        expect("\033[105;5:3u");
    }

    @Test public void acceptedAndRejectedKeypadRepeatsRetainInitialPressIdentity() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 0, KeyEvent.META_NUM_LOCK_ON);
        expect("\033[57399;129u");
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 1, 0);
        expect("\033[57425;1:2u");
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_0, 0, 0);
        expect("\033[57399;1:3u");

        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 0, KeyEvent.META_NUM_LOCK_ON);
        expect("\033[57399;129u");
        byte[] pending = fill();
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 1, 0);
        assertArrayEquals(pending, drain());
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_0, 0, 0);
        expect("\033[57399;1:3u");
        assertEquals(Arrays.asList(TerminalSession.InputRejectionReason.FULL), rejections);
    }

    @Test
    @Config(shadows = KittyKeyboardInputTest.AlternateCharacterMap.class)
    public void acceptedAndRejectedTextRepeatsRetainOriginalLayoutIdentity() {
        flags(7);
        int modifiers = KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON;
        try {
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, modifiers);
            expect("\033[1089:1057:99;6u");
            KittyKeyboardInputTest.AlternateCharacterMap.greek = true;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 1, modifiers);
            expect("\033[960:928:99;6:2u");
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON);
            expect("\033[1089::99;5:3u");

            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, modifiers);
            expect("\033[1089:1057:99;6u");
            byte[] pending = fill();
            KittyKeyboardInputTest.AlternateCharacterMap.greek = true;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 1, modifiers);
            assertArrayEquals(pending, drain());
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON);
            expect("\033[1089::99;5:3u");
        } finally {
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
        }
    }

    @Test public void clientCodePointActionsPrecedeAdmissionAndAreNotRetriedOnFull() {
        flags(7);
        byte[] pending = fill();
        consumeText = true;
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        assertEquals(1, codePointCalls);
        assertArrayEquals(pending, drain());
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        expect("");
        assertTrue(rejections.isEmpty()); // Client consumed its own action; no terminal send was attempted.
    }
}
