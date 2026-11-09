package com.termux.app.terminal;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = EvidenceDockTest.TestApplication.class,
    qualifiers = "w900dp-h1000dp-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
public class KeyboardNavigationTest {
    private TerminalSession session(String name, TermuxShellManager manager) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession terminal = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(terminal, "mEmulator", new TerminalEmulator(terminal, 80, 24, 8, 16, 100, client));
        // The session has no real child process; native PTY calls are shadowed.
        ReflectionHelpers.setField(terminal, "mShellPid", Integer.MAX_VALUE);
        terminal.mSessionName = name;
        ExecutionCommand command = new ExecutionCommand(1, "/unused", new String[0], null, "/",
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(), true);
        TermuxSession session = ReflectionHelpers.callConstructor(TermuxSession.class,
            ClassParameter.from(TerminalSession.class, terminal),
            ClassParameter.from(ExecutionCommand.class, command),
            ClassParameter.from(TermuxSession.TermuxSessionClient.class, null),
            ClassParameter.from(boolean.class, false));
        manager.mTermuxSessions.add(session);
        return terminal;
    }

    private View key(TermuxActivity activity, String label) {
        for (int i = 0; i < activity.getExtraKeysView().getChildCount(); i++) {
            View child = activity.getExtraKeysView().getChildAt(i);
            if (label.equals(child.getContentDescription()) ||
                (child instanceof TextView && label.contentEquals(((TextView) child).getText()))) return child;
        }
        throw new AssertionError("Missing key " + label);
    }

    private ActivityController<TermuxActivity> start() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxAppSharedProperties.init(app);
        TermuxService service = Robolectric.buildService(TermuxService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            new ComponentName(app, TermuxService.class), service.onBind(new Intent(app, TermuxService.class)));
        return Robolectric.buildActivity(TermuxActivity.class).setup();
    }

    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); }

    private void tapFirstCell(TerminalView terminal) {
        long now = android.os.SystemClock.uptimeMillis();
        float x = terminal.mRenderer.getFontWidth() / 2;
        float y = terminal.getPointY(terminal.getTopRow() + 1) - 1;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0);
        assertArrayEquals(new int[]{0, 0}, terminal.getColumnAndRow(up, false));
        terminal.dispatchTouchEvent(down);
        terminal.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        idle();
    }

    @Test public void labeledLinksUseActivePaneCompletedFrameAndExistingClickPreference() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("one", manager), second = session("two", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        Object properties = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(properties, "mMap");
        String key = TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN;
        Object previous = values.put(key, true);
        try {
            activity.getTermuxTerminalSessionClient().setCurrentSession(first);
            TerminalPaneLayoutTest.size(activity.getWindow().getDecorView(), 900, 1000);
            byte[] output = "first".getBytes(StandardCharsets.UTF_8);
            first.getEmulator().append(output, output.length);
            TerminalPaneLayoutTest.capture(activity.getWindow().getDecorView(), "osc8-before-plain-label");
            output = "\033[H\033]8;;https://first.example\033\\first\033]8;;\033\\".getBytes(StandardCharsets.UTF_8);
            first.getEmulator().append(output, output.length);
            TerminalPaneLayoutTest.capture(activity.getWindow().getDecorView(), "osc8-after-linked-label");
            tapFirstCell(activity.getTerminalView());
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertEquals(Intent.ACTION_VIEW, intent.getAction());
            assertEquals("https://first.example", intent.getDataString());

            activity.getTermuxTerminalSessionClient().setCurrentSession(second);
            idle();
            output = ("\r\n".repeat(second.getEmulator().getRows() + 1) +
                "\033[H\033]8;;https://second.example\033\\second\033]8;;\033\\\033[?2026h\033[H\033]8;;https://pending.example\033\\pending\033]8;;\033\\").getBytes(StandardCharsets.UTF_8);
            second.getEmulator().append(output, output.length);
            assertTrue(second.getEmulator().getScreen().getActiveTranscriptRows() > 0);
            activity.getTerminalView().setTopRow(-1);
            tapFirstCell(activity.getTerminalView());
            assertEquals("https://second.example", Shadows.shadowOf(activity).getNextStartedActivity().getDataString());

            values.put(key, false);
            tapFirstCell(activity.getTerminalView());
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            values.put(key, true);
            output = "\033[?2026l\033[2J\033[Hhttps://ordinary.example\033[?2026h\033[Hpending".getBytes(StandardCharsets.UTF_8);
            second.getEmulator().append(output, output.length);
            activity.getTerminalView().setTopRow(-1);
            tapFirstCell(activity.getTerminalView());
            assertEquals("https://ordinary.example", Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
        } finally {
            if (previous == null) values.remove(key); else values.put(key, previous);
            controller.pause().stop().destroy();
            manager.mTermuxSessions.clear();
        }
    }

    private void settleDrawer(TermuxActivity activity) {
        androidx.drawerlayout.widget.DrawerLayout drawer = activity.getDrawer();
        int gravity = activity.getTerminalSessionDrawerGravity();
        // This fixture measures/layouts views directly. Advancing the looper alone does not
        // supply the computeScroll calls that real draw traversals give an animated drawer.
        // Drive its actual ViewDragHelper animation; never force the drawer into an open state.
        for (int frame = 0; frame < 120 && !drawer.isDrawerOpen(gravity); frame++) {
            drawer.computeScroll();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        }
    }

    private String input(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[128];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    @Test public void unmatchedCtrlAltReachesActivePaneAndRecognizedShortcutStillSwitches() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("one", manager), second = session("two", manager);
        ActivityController<TermuxActivity> controller = start();
        try {
            TermuxActivity activity = controller.get();
            View root = activity.findViewById(com.termux.R.id.activity_termux_root_view);
            TerminalPaneLayoutTest.size(root, 900, 650);
            activity.getTerminalPanes().openBeside(first);
            TerminalPaneLayoutTest.size(root, 900, 650);
            idle();
            assertSame(first, activity.getCurrentSession());
            int modifiers = KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;

            assertTrue(activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_X, 0, modifiers)));
            assertEquals("\033\u0018", input(first));
            assertEquals("", input(second));

            assertTrue(activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_N, 0, modifiers)));
            assertSame(second, activity.getCurrentSession());
            assertEquals("", input(first));
            assertEquals("", input(second));

            assertTrue(activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_X, 0, modifiers)));
            assertEquals("", input(first));
            assertEquals("\033\u0018", input(second));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void negotiatedInputRetainsFoldShortcutAndPaneReleasePrecedence() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("one", manager), second = session("two", manager);
        for (TerminalSession terminal : new TerminalSession[]{first, second}) {
            byte[] negotiate = "\033[>7u".getBytes(StandardCharsets.UTF_8);
            terminal.getEmulator().append(negotiate, negotiate.length);
        }
        ActivityController<TermuxActivity> controller = start();
        try {
            TermuxActivity activity = controller.get();
            View root = activity.findViewById(com.termux.R.id.activity_termux_root_view);
            TerminalPaneLayoutTest.size(root, 900, 650);
            activity.getTerminalPanes().openBeside(first);
            TerminalPaneLayoutTest.size(root, 900, 650);
            idle();
            assertSame(first, activity.getCurrentSession());
            int modifiers = KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;

            assertTrue(activity.dispatchKeyEvent(new KeyEvent(100, 100, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_X, 0, modifiers)));
            assertEquals("\033[120;7u", input(first));
            assertEquals("", input(second));
            assertTrue(activity.dispatchKeyEvent(new KeyEvent(200, 200, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_N, 0, modifiers)));
            assertSame(second, activity.getCurrentSession());
            assertTrue(activity.dispatchKeyEvent(new KeyEvent(100, 300, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_X, 0, modifiers)));
            assertEquals("A release from the old pane must not enter either terminal", "", input(first));
            assertEquals("", input(second));

            assertTrue(activity.dispatchKeyEvent(new KeyEvent(400, 400, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_X, 0, modifiers)));
            assertEquals("\033[120;7u", input(second));
            assertEquals("", input(first));
            assertTrue(activity.dispatchKeyEvent(new KeyEvent(500, 500, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, 0, modifiers)));
            settleDrawer(activity);
            assertTrue("The real drawer animation must finish after the recognized shortcut",
                activity.getDrawer().isDrawerOpen(activity.getTerminalSessionDrawerGravity()));
            assertEquals("Recognized drawer shortcut must not produce Kitty bytes", "", input(second));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(shadows = {TerminalPaneLayoutTest.NoPty.class, TerminalImeComposingTest.RecordingImm.class})
    public void composingConnectionRestartsAfterActualFinishedSessionRemoval() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("one", manager), second = session("two", manager);
        com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences preferences =
            com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences.build(app, true);
        assertNotNull(preferences);
        preferences.setImeComposingEnabled(true);
        ActivityController<TermuxActivity> controller = start();
        try {
            TermuxActivity activity = controller.get();
            TerminalPaneLayoutTest.size(activity.findViewById(com.termux.R.id.activity_termux_root_view), 900, 650);
            activity.getTermuxTerminalSessionClient().setCurrentSession(first);
            assertTrue(activity.getTerminalView().requestFocus());
            idle();
            InputConnection old = activity.getTerminalView().onCreateInputConnection(new EditorInfo());
            old.setComposingText("old preedit", 1);
            // Give the fake finished process the real service exit callback, without starting a PTY.
            TermuxSession existing = manager.mTermuxSessions.get(0);
            manager.mTermuxSessions.set(0, ReflectionHelpers.callConstructor(TermuxSession.class,
                ClassParameter.from(TerminalSession.class, first),
                ClassParameter.from(ExecutionCommand.class, existing.getExecutionCommand()),
                ClassParameter.from(TermuxSession.TermuxSessionClient.class, activity.getTermuxService()),
                ClassParameter.from(boolean.class, false)));
            ReflectionHelpers.setField(first, "mShellPid", -1);
            TerminalImeComposingTest.RecordingImm.clear();
            activity.getTermuxTerminalSessionClient().removeFinishedSession(first);
            assertEquals(1, activity.getTermuxService().getTermuxSessionsSize());
            assertSame(second, activity.getCurrentSession());
            idle();
            assertTrue(TerminalImeComposingTest.RecordingImm.restarts.contains(activity.getTerminalView()));
            assertFalse(old.commitText("late", 1));
            InputConnection current = activity.getTerminalView().onCreateInputConnection(new EditorInfo());
            current.commitText("new target", 1);
            assertEquals("new target", input(second));
            assertEquals("", input(first));
        } finally {
            preferences.setImeComposingEnabled(false);
            controller.pause().stop().destroy();
        }
    }

    @Test public void savedControlsApplyOnReturnWithoutReplacingTheSession() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession terminal = session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        idle();
        controller.pause().stop();
        assertTrue(new com.termux.app.terminal.io.ExtraKeysLayout("[[ESC]]").save(app));
        controller.restart().start().resume().visible();
        TerminalPaneLayoutTest.size(activity.findViewById(com.termux.R.id.activity_termux_root_view), 400, 650);
        assertEquals(1, activity.getExtraKeysView().getChildCount());
        assertNotNull(key(activity, "ESC"));
        assertSame(terminal, activity.getCurrentSession());

        controller.pause().stop();
        assertTrue(com.termux.app.terminal.io.ExtraKeysLayout.useFile(app));
        controller.restart().start().resume().visible();
        TerminalPaneLayoutTest.size(activity.findViewById(com.termux.R.id.activity_termux_root_view), 400, 650);
        assertNotNull(key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)));
        int[] created = {0};
        TermuxTerminalSessionActivityClient client = new TermuxTerminalSessionActivityClient(activity) {
            @Override public void addNewSession(boolean failsafe, String name) {
                assertFalse(failsafe);
                assertNull(name);
                created[0]++;
            }
        };
        activity.getExtraKeysView().setExtraKeysViewClient(new com.termux.app.terminal.io.TermuxTerminalExtraKeys(
            activity, activity.getTerminalView(), activity.getTermuxTerminalViewClient(), client));
        key(activity, "New session").performClick();
        assertEquals(1, created[0]);
        assertSame(terminal, activity.getCurrentSession());
        controller.pause().stop().destroy();
    }

    @Test public void returnThroughActivityLifecycleKeepsKeyboardHidden() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession terminal = session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        TerminalPaneLayoutTest.size(activity.findViewById(com.termux.R.id.activity_termux_root_view), 900, 650);
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        idle();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
        idle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getTerminalView().hasFocus());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
        assertEquals("a", input(terminal));
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().resume().visible().windowFocusChanged(true);
        idle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().stop().destroy();
    }

    @Test public void navigationPropertyRestoresAutomaticShowing() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        Object properties = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(properties, "mMap");
        String key = TermuxPropertyConstants.KEY_SHOW_SOFT_KEYBOARD_ON_NAVIGATION;
        Object previous = values.put(key, true);
        try {
            InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            idle();
            assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
            key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
            idle();
            assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        } finally {
            if (previous == null) values.remove(key); else values.put(key, previous);
            controller.pause().stop().destroy();
        }
    }

    @Test public void startupHideAndDisabledKeyboardPreferencesRemainEffective() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        Object properties = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(properties, "mMap");
        String key = TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP;
        Object previous = values.put(key, true);
        try {
            InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            idle();
            controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
            idle();
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
            assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
            controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
            idle();
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            activity.getPreferences().setSoftKeyboardEnabled(false);
            controller.pause().resume().visible().windowFocusChanged(true);
            key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            assertFalse(activity.getPreferences().isSoftKeyboardEnabled());
        } finally {
            if (previous == null) values.remove(key); else values.put(key, previous);
            activity.getPreferences().setSoftKeyboardEnabled(true);
            controller.pause().stop().destroy();
        }
    }

    @Test public void enableDisableKeyboardControlStillWorksAcrossReturn() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        Object properties = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(properties, "mMap");
        String key = TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR;
        Object previous = values.put(key, TermuxPropertyConstants.IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE);
        try {
            InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            idle();
            key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
            assertFalse(activity.getPreferences().isSoftKeyboardEnabled());
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
            idle();
            assertFalse(activity.getPreferences().isSoftKeyboardEnabled());
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
            idle();
            assertTrue(activity.getPreferences().isSoftKeyboardEnabled());
            assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
            key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
            assertFalse(activity.getPreferences().isSoftKeyboardEnabled());
        } finally {
            if (previous == null) values.remove(key); else values.put(key, previous);
            activity.getPreferences().setSoftKeyboardEnabled(true);
            controller.pause().stop().destroy();
        }
    }

    @Test public void quickReturnAfterPauseKeepsKeyboardHidden() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        idle();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().resume().visible().windowFocusChanged(true);
        idle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getTerminalView().hasFocus());
        controller.pause().stop().destroy();
    }

    @Test public void keyboardControlCancelsPendingAutomaticShow() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        input.showSoftInput(activity.getTerminalView(), 0);
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        idle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().stop().destroy();
    }

    @Test @Config(sdk = 37)
    public void hidingKeyboardCancelsPendingAutomaticShow() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        session("one", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View root = activity.findViewById(com.termux.R.id.activity_termux_root_view);
        input.showSoftInput(activity.getTerminalView(), 0);
        root.dispatchApplyWindowInsets(new WindowInsets.Builder().setVisible(WindowInsets.Type.ime(), true).build());
        input.hideSoftInputFromWindow(activity.getTerminalView().getWindowToken(), 0);
        root.dispatchApplyWindowInsets(new WindowInsets.Builder().setVisible(WindowInsets.Type.ime(), false).build());
        idle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().stop().destroy();
    }

    @Test public void nextPreservesKeyboardVisibilityAcrossExistingPanes() {
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("one", manager), second = session("two", manager);
        ActivityController<TermuxActivity> controller = start();
        TermuxActivity activity = controller.get();
        View root = activity.findViewById(com.termux.R.id.activity_termux_root_view);
        TerminalPaneLayoutTest.size(root, 900, 650);
        activity.getTerminalPanes().openBeside(first);
        TerminalPaneLayoutTest.size(root, 900, 650);
        idle();
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        assertSame(first, activity.getCurrentSession());
        assertNotNull(activity.getTerminalView().mEmulator);
        assertEquals(2, activity.getTermuxService().getTermuxSessionsSize());
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, "NEXT").performClick();
        idle();
        assertSame(second, activity.getCurrentSession());
        assertNotNull(activity.getTerminalView().mEmulator);
        assertTrue(activity.getTerminalView().hasFocus());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
        assertEquals("a", input(second));
        key(activity, activity.getString(com.termux.shared.R.string.extra_keyboard)).performClick();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, "NEXT").performClick();
        idle();
        assertSame(first, activity.getCurrentSession());
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
        input.hideSoftInputFromWindow(activity.getTerminalView().getWindowToken(), 0);
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, "NEXT").performClick();
        idle();
        assertSame(second, activity.getCurrentSession());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().stop().destroy();
    }
}
