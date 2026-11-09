package com.termux.app.terminal;

import android.app.Application;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

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
    private boolean consumeUp;

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
            @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return consumeUp; }
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
        // Existing repeat tests also fill every ordinary byte while an initial UP is held.
        int held = ReflectionHelpers.getField(queue, "mReservedBytes");
        int stored = ReflectionHelpers.getField(queue, "mStoredBytes");
        byte[] pending = new byte[capacity - held - stored];
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

    /** Fill through production Session admission until even one ordinary byte is rejected.
     * No reservation fields or queue writes are used: this reproduces the old full-on-UP loss. */
    private byte[] fillUntilOrdinaryFull() {
        int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
        int accepted = 0;
        for (int size = capacity; size > 0; size /= 2) {
            byte[] chunk = new byte[size];
            Arrays.fill(chunk, (byte) 'P');
            while (session.tryWrite(chunk, 0, chunk.length)) {
                accepted += size;
                assertTrue("Admission exceeded the fixed ring", accepted <= capacity);
            }
        }
        assertFalse(session.tryWrite("!"));
        byte[] pending = new byte[accepted];
        Arrays.fill(pending, (byte) 'P');
        return pending;
    }

    private void expectPendingThen(byte[] pending, String suffix) {
        byte[] tail = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] expected = Arrays.copyOf(pending, pending.length + tail.length);
        System.arraycopy(tail, 0, expected, pending.length, tail.length);
        assertArrayEquals(expected, drain());
    }

    @Test public void acceptedFunctionalDownRetainsReleaseWhenOrdinaryInputStaysFullThroughUp() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        byte[] pending = fillUntilOrdinaryFull();
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0); // No intervening drain.
        expectPendingThen(pending, "\033[1;1:3A");
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("");
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
    }

    @Test public void acceptedTextAndLegacyCtrlIDownsRetainReleaseWhileFullThroughUp() {
        for (int mode : new int[]{2, 3, 7}) {
            flags(mode);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            expect(mode == 2 ? "\t" : "\033[105;5u");
            byte[] pending = fillUntilOrdinaryFull();
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            expectPendingThen(pending, "\033[105;5:3u");
        }
    }

    @Test public void downIsRejectedWhenOnlyItsOwnBytesFitWithoutFutureRelease() {
        for (int code : new int[]{KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_I}) {
            flags(3);
            int modifiers = code == KeyEvent.KEYCODE_I ? KeyEvent.META_CTRL_ON : 0;
            int downLength = (code == KeyEvent.KEYCODE_I ? "\033[105;5u" : "\033[A").getBytes(StandardCharsets.UTF_8).length;
            int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
            byte[] pending = new byte[capacity - downLength];
            assertTrue(session.tryWrite(pending, 0, pending.length));
            key(KeyEvent.ACTION_DOWN, code, 0, modifiers);
            assertArrayEquals(pending, drain());
            key(KeyEvent.ACTION_UP, code, 0, modifiers);
            expect("");
            assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
        }
    }

    private static int allEventModifiers() {
        return KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON |
            KeyEvent.META_CTRL_ON | KeyEvent.META_META_ON | KeyEvent.META_CAPS_LOCK_ON | KeyEvent.META_NUM_LOCK_ON;
    }

    @Test public void liveFunctionalUpModifiersCanGrowToThreeDigitsWhileOrdinaryInputIsFull() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        byte[] pending = fillUntilOrdinaryFull();
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, allEventModifiers());
        expectPendingThen(pending, "\033[1;208:3A");
    }

    @Test @Config(shadows = KittyKeyboardInputTest.AlternateCharacterMap.class)
    public void liveTextUpModifiersAndCapturedShiftAlternateFitTheHeldEncoderBound() {
        flags(7);
        try {
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
            expect("\033[1089:1057:99;6u");
            byte[] pending = fillUntilOrdinaryFull();
            KittyKeyboardInputTest.AlternateCharacterMap.greek = true;
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, allEventModifiers());
            expectPendingThen(pending, "\033[1089:1057:99;16:3u");
        } finally {
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
        }
    }

    @Test public void twoHeldKeysSpendOnlyTheirOwnCapacityAndRefundUnusedBytesOnce() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0);
        expect("\033[B");
        byte[] pending = fillUntilOrdinaryFull();
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        assertTrue(session.tryWrite("xx")); // 208 -> 1 returns the two unused modifier digits.
        assertFalse(session.tryWrite("!"));
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0);
        expectPendingThen(pending, "\033[1;1:3Axx\033[1;1:3B");
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0);
        expect("");
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
    }

    @Test public void protocolAndScreenChangesReclaimCapacityBeforeAnotherOrdinarySessionWrite() {
        for (String transition : new String[]{"\033[=7u", "\033[?1049h"}) {
            flags(3);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            expect("\033[A");
            byte[] command = transition.getBytes(StandardCharsets.UTF_8);
            session.getEmulator().append(command, command.length);
            int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
            byte[] pending = new byte[capacity];
            assertTrue(session.tryWrite(pending, 0, pending.length)); // Session pruning, before any view callback.
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            assertArrayEquals(pending, drain());
        }
    }

    @Test public void windowLossDetachCanceledAndClientConsumedUpsReturnCapacityWithoutGhosts() {
        for (int cancellation = 0; cancellation < 4; cancellation++) {
            flags(3);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            expect("\033[A");
            if (cancellation == 0) view.onWindowFocusChanged(false);
            else if (cancellation == 1) ReflectionHelpers.callInstanceMethod(view, "onDetachedFromWindow");
            else if (cancellation == 2) {
                KeyEvent canceled = new KeyEvent(10, 12, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_CANCELED);
                assertTrue(view.onKeyUp(KeyEvent.KEYCODE_DPAD_UP, canceled));
            } else {
                consumeUp = true;
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
                consumeUp = false;
            }
            int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
            byte[] pending = new byte[capacity];
            assertTrue(session.tryWrite(pending, 0, pending.length));
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            assertArrayEquals(pending, drain());
        }
    }


    @Test public void inputConnectionReplacementDoesNotCancelAStillValidPhysicalRelease() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        int held = ReflectionHelpers.getField(queue, "mReservedBytes");
        assertTrue(held > 0);
        InputConnection old = view.onCreateInputConnection(new EditorInfo());
        InputConnection current = view.onCreateInputConnection(new EditorInfo());
        assertNotNull(old);
        assertNotNull(current);
        old.closeConnection();
        assertEquals(held, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
        byte[] pending = fillUntilOrdinaryFull();
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expectPendingThen(pending, "\033[1;1:3A");
        current.closeConnection();
    }

    @Test public void screenUpdatePrunesAllStaleHoldersWithoutSendingSyntheticReleases() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0);
        expect("\033[A\033[B");
        flags(7);
        view.onScreenUpdated(true);
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
        byte[] pending = fill();
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0);
        assertArrayEquals(pending, drain());
    }

    @Test public void switchingSessionsCancelsTheOriginalPromiseWithoutWritingIntoEitherNewContext() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        TerminalSession other = new TerminalSession("/unused", "/", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        TerminalEmulator emulator = new TerminalEmulator(other, 20, 4, 8, 16, 100,
            new TermuxTerminalSessionClientBase());
        ReflectionHelpers.setField(other, "mEmulator", emulator);
        ReflectionHelpers.setField(other, "mShellPid", 1);
        Object otherQueue = ReflectionHelpers.getField(other, "mTerminalToProcessIOQueue");
        try {
            assertTrue(view.attachSession(other));
            view.mEmulator = emulator; // Same no-layout/no-PTY fixture convention as setUp.
            int capacity = ReflectionHelpers.getStaticField(TerminalSession.class, "MAX_PENDING_INPUT_BYTES");
            byte[] pending = new byte[capacity];
            assertTrue(session.tryWrite(pending, 0, pending.length));
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            assertArrayEquals(pending, drain());
            int count = ReflectionHelpers.callInstanceMethod(otherQueue, "read",
                ClassParameter.from(byte[].class, new byte[32]), ClassParameter.from(boolean.class, false));
            assertEquals(0, count);
        } finally {
            ReflectionHelpers.callInstanceMethod(otherQueue, "close");
        }
    }


    @Test public void firstAdmittedFunctionalRepeatOwnsItsReleaseWhenInitialDownWasFull() {
        for (int mode : new int[]{2, 3}) {
            flags(mode);
            byte[] pending = fillUntilOrdinaryFull();
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            assertArrayEquals(pending, drain());
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 1, 0);
            expect("\033[1;1:2A");
            assertTrue((int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes") > 0);
            pending = fillUntilOrdinaryFull();
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 2, 0); // Rejected repeat cannot lose the first token.
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, allEventModifiers());
            expectPendingThen(pending, "\033[1;208:3A"); // UP before any drain, with growing live modifiers.
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
            expect("");
            assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
        }
    }

    @Test public void firstAdmittedShortcutRepeatOwnsItsReleaseAfterFullLegacyOrEncodedDown() {
        for (int mode : new int[]{2, 3, 7}) {
            flags(mode);
            byte[] pending = fillUntilOrdinaryFull();
            int before = codePointCalls;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            assertArrayEquals(pending, drain()); // Flags2 rejected the legacy TAB, not an encoded press.
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 1, KeyEvent.META_CTRL_ON);
            expect("\033[105;5:2u");
            assertTrue((int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes") > 0);
            pending = fillUntilOrdinaryFull();
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 2, KeyEvent.META_CTRL_ON);
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, allEventModifiers());
            expectPendingThen(pending, "\033[105;16:3u");
            assertEquals(before + 3, codePointCalls); // Client hooks precede both admission and rejection.
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
            expect("");
            assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
        }
    }

    @Test @Config(shadows = KittyKeyboardInputTest.AlternateCharacterMap.class)
    public void firstAdmittedRepeatCapturesItsActualIdentityWithoutLaterReplacement() {
        flags(3);
        byte[] pending = fillUntilOrdinaryFull();
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 0, KeyEvent.META_NUM_LOCK_ON);
        assertArrayEquals(pending, drain());
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 1, 0);
        expect("\033[57425;1:2u");
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 2, KeyEvent.META_NUM_LOCK_ON);
        expect("\033[57399;129:2u");
        pending = fillUntilOrdinaryFull();
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_0, 0, KeyEvent.META_NUM_LOCK_ON);
        expectPendingThen(pending, "\033[57425;129:3u");
        flags(7);
        int modifiers = KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON;
        try {
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
            pending = fillUntilOrdinaryFull();
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, modifiers);
            assertArrayEquals(pending, drain());
            KittyKeyboardInputTest.AlternateCharacterMap.greek = true;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 1, modifiers);
            expect("\033[960:928:99;6:2u");
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 2, modifiers);
            expect("\033[1089:1057:99;6:2u");
            pending = fillUntilOrdinaryFull();
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, allEventModifiers());
            expectPendingThen(pending, "\033[960:928:99;16:3u");
        } finally {
            KittyKeyboardInputTest.AlternateCharacterMap.greek = false;
        }
    }

    @Test public void untrackedRepeatsDoNotGateLegacyShortcutPlainTextOrRecoveryInput() {
        flags(1); // No event reporting: a shortcut repeat is still an ordinary encoded press.
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 1, KeyEvent.META_CTRL_ON);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0, KeyEvent.META_CTRL_ON);
        expect("\033[105;5u");
        flags(0);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 1, 0);
        expect("\033[A");
        for (int mode : new int[]{2, 3, 7}) {
            flags(mode);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 1, 0);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 1, 0);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1, 0);
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 1, 0);
            expect("i\t\r\177");
        }
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
    }

    @Test public void unrelatedRepeatDownTimeCannotReplaceOrCancelAnAcceptedInitialPromise() {
        flags(3);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[A");
        int held = ReflectionHelpers.getField(queue, "mReservedBytes");
        KeyEvent unrelated = new KeyEvent(20, 21, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP,
            1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, unrelated));
        expect("");
        assertEquals(held, (int) ReflectionHelpers.<Integer>getField(queue, "mReservedBytes"));
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        expect("\033[1;1:3A");
    }

}
