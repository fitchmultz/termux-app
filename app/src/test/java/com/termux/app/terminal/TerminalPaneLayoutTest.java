package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = Application.class, qualifiers = "w900dp-h1000dp-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
public class TerminalPaneLayoutTest {
    @Implements(className = "com.termux.terminal.JNI", isInAndroidSdk = false)
    public static class NoPty {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void setPtyWindowSize(int fd, int rows, int columns, int width, int height) {}
    }

    private TerminalPaneLayout layout(Activity activity) {
        TerminalPaneLayout panes = new TerminalPaneLayout(activity, null);
        for (TerminalView terminal : panes.getTerminals()) {
            terminal.setTerminalViewClient(new TermuxTerminalViewClientBase());
            panes.setFontSize(terminal, 16);
        }
        activity.setContentView(panes);
        size(panes, 900, 1000);
        return panes;
    }

    private void size(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        // A fold transition changes child visibility in onSizeChanged; finish its requested layout.
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private TerminalSession session(String name) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, client));
        ReflectionHelpers.setField(session, "mShellPid", 1234);
        session.mSessionName = name;
        byte[] output = ("\033[1;36m" + name + "\033[0m\r\n\r\nIndependent terminal output\r\n$ npm test\r\nTests: 24 passed\r\n\r\nReady for your next command.\r\n").getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(output, output.length);
        return session;
    }

    private void capture(View view, String name) throws Exception {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.io.File directory = new java.io.File("build/reports/tests/workbench");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private String input(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read", ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private static class Keys extends TerminalExtraKeys {
        Keys(TerminalView terminal) { super(terminal); }
        void type(String text) { onTerminalExtraKeyButtonClick(null, text, false, false, false, false); }
    }

    @Test
    public void touchFocusRoutesExtraKeysWithoutReattachingOrDuplicatingSessions() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout panes = layout(activity);
        TerminalSession a = session("Agent"), b = session("Tests");
        Keys keys = new Keys(panes.getActiveTerminal());
        panes.setOnActiveChanged(keys::setTerminalView);
        panes.showSession(a);
        panes.openBeside(b);
        size(panes, 900, 1000);
        keys.type("b");
        assertEquals("", input(a));
        assertEquals("b", input(b));
        TerminalView first = panes.getTerminals()[0];
        byte[] history = new String(new char[100]).replace("\0", "output line\r\n").getBytes(StandardCharsets.UTF_8);
        a.getEmulator().append(history, history.length);
        first.setTopRow(-2);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0);
        first.dispatchTouchEvent(down);
        down.recycle();
        keys.type("a");
        assertSame(first, panes.getActiveTerminal());
        assertEquals("a", input(a));
        assertEquals(-2, first.getTopRow());
        assertEquals("", input(b));
        assertSame(a, first.getCurrentSession());
        assertSame(b, panes.getTerminals()[1].getCurrentSession());
        panes.showSession(b);
        assertSame(b, panes.getActiveTerminal().getCurrentSession());
        assertSame(a, first.getCurrentSession());
        panes.setFontSize(panes.getActiveTerminal(), 24);
        assertEquals(16, panes.getFontSize(first));
        assertEquals(24, panes.getFontSize(panes.getActiveTerminal()));
        activity.finish();
    }

    @Test
    public void foldingMaximizingAndSwappingKeepTheActiveSessionAndRestoreThePair() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout panes = layout(activity);
        TerminalSession a = session("Agent"), b = session("Tests");
        panes.showSession(a);
        panes.openBeside(b);
        size(panes, 900, 1000);
        assertTrue(panes.isShowingBoth());
        assertNotEquals(a.getEmulator().mColumns, 80);
        capture(panes, "split-wide");
        panes.setFraction(0.35f);
        panes.setSplitOrientation(LinearLayout.VERTICAL);
        panes.swap();
        size(panes, 400, 1000);
        assertFalse(panes.isShowingBoth());
        assertSame(b, panes.getActiveTerminal().getCurrentSession());
        assertTrue(a.isRunning());
        capture(panes, "split-cover");
        size(panes, 900, 1000);
        assertTrue(panes.isShowingBoth());
        assertEquals(LinearLayout.VERTICAL, panes.getOrientation());
        assertEquals(0.35f, panes.getFraction(), 0.001f);
        panes.toggleMaximize();
        assertFalse(panes.isShowingBoth());
        panes.toggleMaximize();
        assertTrue(panes.isShowingBoth());
        panes.setFraction(0.01f);
        assertEquals(0.25f, panes.getFraction(), 0);
        panes.setFraction(0.99f);
        assertEquals(0.75f, panes.getFraction(), 0);
        activity.finish();
    }

    @Test
    public void activityStateRestoresExistingSessionsAndRemovalDoesNotStealFocus() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout panes = layout(activity);
        TerminalSession a = session("Agent"), b = session("Tests"), background = session("Background");
        panes.showSession(a);
        panes.openBeside(b);
        panes.setFontSize(panes.getActiveTerminal(), 20);
        Bundle state = panes.saveState();
        TerminalPaneLayout restored = layout(activity);
        restored.restoreState(state, handle -> a.mHandle.equals(handle) ? a : b.mHandle.equals(handle) ? b : null);
        size(restored, 900, 1000);
        assertSame(b, restored.getActiveTerminal().getCurrentSession());
        assertTrue(restored.isShowingBoth());
        assertEquals(20, restored.getFontSize(restored.getActiveTerminal()));
        restored.removeSession(background);
        assertSame(b, restored.getActiveTerminal().getCurrentSession());
        restored.removeSession(a);
        assertSame(b, restored.getActiveTerminal().getCurrentSession());
        assertFalse(restored.isPaired());
        restored.openBeside(a);
        restored.removeSession(a);
        assertSame(b, restored.getActiveTerminal().getCurrentSession());
        activity.finish();
    }
}
