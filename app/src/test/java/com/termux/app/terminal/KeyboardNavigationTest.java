package com.termux.app.terminal;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
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
            if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) return child;
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

    private String input(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[128];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
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
        key(activity, "⌨").performClick();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
        idle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getTerminalView().hasFocus());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
        assertEquals("a", input(terminal));
        key(activity, "⌨").performClick();
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
            key(activity, "⌨").performClick();
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
            key(activity, "⌨").performClick();
            assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
            controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
            idle();
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            activity.getPreferences().setSoftKeyboardEnabled(false);
            controller.pause().resume().visible().windowFocusChanged(true);
            key(activity, "⌨").performClick();
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
            key(activity, "⌨").performClick();
            assertFalse(activity.getPreferences().isSoftKeyboardEnabled());
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            controller.pause().stop().restart().start().resume().visible().windowFocusChanged(true);
            idle();
            assertFalse(activity.getPreferences().isSoftKeyboardEnabled());
            assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
            key(activity, "⌨").performClick();
            idle();
            assertTrue(activity.getPreferences().isSoftKeyboardEnabled());
            assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
            key(activity, "⌨").performClick();
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
        key(activity, "⌨").performClick();
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
        key(activity, "⌨").performClick();
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
        key(activity, "⌨").performClick();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        key(activity, "NEXT").performClick();
        idle();
        assertSame(second, activity.getCurrentSession());
        assertNotNull(activity.getTerminalView().mEmulator);
        assertTrue(activity.getTerminalView().hasFocus());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
        assertEquals("a", input(second));
        key(activity, "⌨").performClick();
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
