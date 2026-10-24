package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
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
@Config(sdk = {28, 37}, application = Application.class, qualifiers = "w900dp-h1000dp-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
public class TerminalPaneLayoutTest {
    @Implements(className = "com.termux.terminal.JNI", isInAndroidSdk = false)
    public static class NoPty {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void setPtyWindowSize(int fd, int rows, int columns, int width, int height) {}
    }

    private TerminalPaneLayout layout(Activity activity) {
        TerminalPaneLayout panes = unmeasuredLayout(activity);
        size(panes, 900, 1000);
        return panes;
    }

    private TerminalPaneLayout unmeasuredLayout(Activity activity) {
        TerminalPaneLayout panes = new TerminalPaneLayout(activity, null);
        for (TerminalView terminal : panes.getTerminals()) {
            terminal.setTerminalViewClient(new TermuxTerminalViewClientBase());
            panes.setFontSize(terminal, 16);
        }
        activity.setContentView(panes);
        return panes;
    }

    static void size(View view, int width, int height) {
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

    private static Bitmap render(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        return bitmap;
    }

    static void capture(View view, String name) throws Exception {
        Bitmap bitmap = render(view);
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

    private void history(TerminalSession session) {
        byte[] bytes = lines(180).getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(bytes, bytes.length);
    }

    private String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append("line ").append(i).append("\r\n");
        return text.toString();
    }

    private int pageUp(TerminalView terminal) {
        assertTrue(terminal.handleKeyCodeAction(android.view.KeyEvent.KEYCODE_PAGE_UP, KeyHandler.KEYMOD_SHIFT));
        assertTrue(terminal.getTopRow() < 0);
        return terminal.getTopRow();
    }

    /** Use the real session handler and activity client, replacing only native PTY I/O. */
    private TermuxActivity notificationActivity() {
        return notificationActivity(Robolectric.buildActivity(TermuxActivity.class));
    }

    private TermuxActivity notificationActivity(ActivityController<TermuxActivity> controller) {
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        TerminalPaneLayout panes = layout(activity);
        ReflectionHelpers.setField(activity, "mTerminalPanes", panes);
        ReflectionHelpers.setField(activity, "mTerminalView", panes.getActiveTerminal());
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        TermuxTerminalSessionActivityClient client = new TermuxTerminalSessionActivityClient(activity);
        ReflectionHelpers.setField(activity, "mTermuxTerminalSessionActivityClient", client);
        ReflectionHelpers.setField(activity, "mTermuxTerminalViewClient", new TermuxTerminalViewClient(activity, client));
        return activity;
    }

    private void output(TerminalSession session, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Object queue = ReflectionHelpers.getField(session, "mProcessToTerminalIOQueue");
        assertTrue(ReflectionHelpers.callInstanceMethod(queue, "write",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(int.class, 0), ClassParameter.from(int.class, bytes.length)));
        Handler handler = ReflectionHelpers.getField(session, "mMainThreadHandler");
        handler.sendEmptyMessage(ReflectionHelpers.getStaticField(TerminalSession.class, "MSG_NEW_INPUT"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private TermuxTerminalExtraKeys scrollKey(TermuxActivity activity) {
        return new TermuxTerminalExtraKeys(activity, activity.getTerminalView(),
            activity.getTermuxTerminalViewClient(), activity.getTermuxTerminalSessionClient());
    }

    @Test
    public void sessionSwitchAndPaneReopeningRestoreIndependentScrollOffsets() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout panes = layout(activity);
        TerminalSession a = session("Agent"), b = session("Tests"), c = session("Background");
        panes.showSession(a);
        history(a);
        int topA = pageUp(panes.getActiveTerminal());
        panes.showSession(c);
        assertEquals(0, panes.getActiveTerminal().getTopRow());
        panes.showSession(a);
        assertEquals(topA, panes.getActiveTerminal().getTopRow());

        panes.openBeside(b);
        size(panes, 900, 1000);
        history(b);
        panes.getActiveTerminal().setTopRow(-3);
        panes.singlePane();
        size(panes, 900, 1000);
        panes.showSession(a);
        assertEquals(topA, panes.getActiveTerminal().getTopRow());
        panes.openBeside(b);
        size(panes, 900, 1000);
        assertEquals(-3, panes.getActiveTerminal().getTopRow());
        assertEquals(topA, panes.getTerminals()[1].getTopRow());
        activity.finish();
    }

    @Test
    public void keyboardHeightAndColumnReflowKeepOffsetsWithinRemainingHistory() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout panes = layout(activity);
        TerminalSession session = session("Agent");
        panes.showSession(session);
        history(session);
        TerminalView terminal = panes.getActiveTerminal();
        int top = pageUp(terminal);
        int rows = session.getEmulator().mRows;
        size(panes, 900, 600);
        assertTrue(session.getEmulator().mRows < rows);
        assertEquals(top, terminal.getTopRow());
        size(panes, 900, 1000);
        assertEquals(top, terminal.getTopRow());
        int columns = session.getEmulator().mColumns;
        size(panes, 700, 1000);
        assertTrue(session.getEmulator().mColumns < columns);
        assertEquals(top, terminal.getTopRow());
        size(panes, 700, 1700);
        int remaining = session.getEmulator().getScreen().getActiveTranscriptRows();
        assertTrue(remaining < -top);
        assertEquals(-remaining, terminal.getTopRow());
        terminal.setTopRow(0);
        size(panes, 900, 1000);
        assertEquals(0, terminal.getTopRow());
        activity.finish();
    }

    @Test
    public void scrollKeyRetainsOutputAnchorAndEvictionWhileNormalOutputStillFollows() {
        TermuxActivity activity = notificationActivity();
        TerminalPaneLayout panes = activity.getTerminalPanes();
        TerminalSession session = session("Agent"), other = session("Other");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        panes.showSession(session);
        output(session, lines(180));
        TerminalView terminal = panes.getActiveTerminal();
        terminal.setTopRow(-3);
        TermuxTerminalExtraKeys keys = scrollKey(activity);
        keys.onTerminalExtraKeyButtonClick(null, "SCROLL", false, false, false, false);
        assertTrue(session.getEmulator().isAutoScrollDisabled());
        String anchor = session.getEmulator().getScreen().getSelectedText(0, -3, 20, -3);
        output(session, "next\r\n");
        assertEquals(-4, terminal.getTopRow());
        assertEquals(anchor, session.getEmulator().getScreen().getSelectedText(0, -4, 20, -4));
        panes.showSession(other);
        assertFalse(other.getEmulator().isAutoScrollDisabled());
        output(session, "detached\r\n");
        panes.showSession(session);
        assertEquals(-5, terminal.getTopRow());
        assertEquals(anchor, session.getEmulator().getScreen().getSelectedText(0, -5, 20, -5));
        panes.showSession(other);
        panes.showSession(session);
        assertEquals(-5, terminal.getTopRow());
        output(session, "attached again\r\n");
        assertEquals(-6, terminal.getTopRow());
        output(session, lines(180));
        int oldest = -session.getEmulator().getScreen().getActiveTranscriptRows();
        assertEquals(oldest, terminal.getTopRow());
        panes.showSession(other);
        panes.showSession(session);
        assertEquals(oldest, terminal.getTopRow());
        keys.onTerminalExtraKeyButtonClick(null, "SCROLL", false, false, false, false);
        output(session, "follow\r\n");
        assertEquals(0, terminal.getTopRow());
        panes.showSession(other);
        panes.showSession(session);
        assertEquals(0, terminal.getTopRow());
    }

    @Test
    public void alternateScreenAndClearedHistoryCannotRestoreInvalidScrollRows() {
        TermuxActivity activity = notificationActivity();
        TerminalPaneLayout panes = activity.getTerminalPanes();
        TerminalSession session = session("Agent"), other = session("Other");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        panes.showSession(session);
        output(session, lines(180));
        TerminalView terminal = panes.getActiveTerminal();
        pageUp(terminal);
        panes.showSession(other);
        output(session, "\033[?1049h");
        panes.showSession(session);
        assertEquals(0, terminal.getTopRow());
        terminal.handleKeyCodeAction(android.view.KeyEvent.KEYCODE_PAGE_UP, KeyHandler.KEYMOD_SHIFT);
        assertEquals(0, terminal.getTopRow());
        assertTrue(input(session).contains("\033[A"));
        output(session, "\033[?1049l");
        assertEquals(0, terminal.getTopRow());
        pageUp(terminal);
        panes.showSession(other);
        output(session, "\033[3J");
        panes.showSession(session);
        assertEquals(0, terminal.getTopRow());
        output(session, lines(180));
        pageUp(terminal);
        output(session, "\033c");
        assertEquals(0, terminal.getTopRow());
        panes.showSession(other);
        panes.showSession(session);
        assertEquals(0, terminal.getTopRow());
    }

    @Test
    public void reattachingAndResizingDuringSynchronizedOutputKeepTheCompletedPresentation() {
        TermuxActivity activity = notificationActivity();
        TerminalPaneLayout panes = activity.getTerminalPanes();
        TerminalSession session = session("Agent"), other = session("Other");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        panes.showSession(session);
        output(session, lines(180));
        TerminalView terminal = panes.getActiveTerminal();
        Bitmap completed = render(terminal);
        int top = -3;
        terminal.setTopRow(top);
        output(session, "\033[?2026h\033[2J\033[HUNFINISHED");
        panes.showSession(other);
        panes.showSession(session);
        assertEquals(top, terminal.getTopRow());
        size(panes, 900, 600);
        assertEquals(top, terminal.getTopRow());
        size(panes, 900, 1000);
        Bitmap deferred = render(terminal);
        assertTrue("Only the completed visible frame may be presented", completed.sameAs(deferred));
        assertEquals(top, terminal.getTopRow());
        output(session, "\033[?2026l");
        assertEquals(0, terminal.getTopRow());
        Bitmap committed = render(terminal);
        assertFalse(completed.sameAs(committed));
        completed.recycle();
        deferred.recycle();
        committed.recycle();
    }

    @Test
    public void restoringScrollLockDuringSynchronizedOutputDefersPendingShiftUntilCommit() {
        TermuxActivity activity = notificationActivity();
        TerminalPaneLayout panes = activity.getTerminalPanes();
        TerminalSession session = session("Agent"), other = session("Other");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        panes.showSession(session);
        output(session, lines(180));
        TerminalView terminal = panes.getActiveTerminal();
        terminal.setTopRow(-3);
        scrollKey(activity).onTerminalExtraKeyButtonClick(null, "SCROLL", false, false, false, false);
        panes.showSession(other);
        output(session, "\033[?2026hnext\r\n");
        panes.showSession(session);
        assertEquals(-3, terminal.getTopRow());
        size(panes, 900, 600);
        assertEquals(-3, terminal.getTopRow());
        output(session, "\033[?2026l");
        assertEquals(-4, terminal.getTopRow());
        output(session, "next\r\n");
        assertEquals(-5, terminal.getTopRow());
    }

    @Test
    public void foregroundRefreshAfterActivityReplacementPreservesTheRestoredOffset() {
        TermuxActivity activity = notificationActivity();
        TerminalPaneLayout panes = activity.getTerminalPanes();
        TerminalSession session = session("Agent");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        panes.showSession(session);
        output(session, lines(180));
        TerminalView original = panes.getActiveTerminal();
        original.setTopRow(-3);
        Bundle state = panes.saveState();
        activity.setContentView(new View(activity));

        TermuxActivity replacement = notificationActivity();
        session.updateTerminalSessionClient(replacement.getTermuxTerminalSessionClient());
        TerminalPaneLayout restored = replacement.getTerminalPanes();
        restored.restoreState(state, handle -> session.mHandle.equals(handle) ? session : null);
        assertNotSame(original, restored.getActiveTerminal());
        assertEquals(-3, restored.getActiveTerminal().getTopRow());
        replacement.getTermuxTerminalSessionClient().onStart();
        assertEquals(-3, restored.getActiveTerminal().getTopRow());
        replacement.getTermuxTerminalSessionClient().onStart();
        assertEquals(-3, restored.getActiveTerminal().getTopRow());
        output(session, "new output\r\n");
        assertEquals(0, restored.getActiveTerminal().getTopRow());
    }

    @Test
    public void foregroundRefreshReconcilesBackgroundScrollLockOnceAndDefersSynchronizedOutput() {
        TermuxActivity activity = notificationActivity();
        TerminalSession session = session("Agent");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        activity.getTerminalPanes().showSession(session);
        output(session, lines(180));
        TerminalView terminal = activity.getTerminalView();
        terminal.setTopRow(-3);
        scrollKey(activity).onTerminalExtraKeyButtonClick(null, "SCROLL", false, false, false, false);
        ReflectionHelpers.setField(activity, "mIsVisible", false);
        output(session, "background\r\n");
        assertEquals(-3, terminal.getTopRow());
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        activity.getTermuxTerminalSessionClient().onStart();
        assertEquals(-4, terminal.getTopRow());
        activity.getTermuxTerminalSessionClient().onStart();
        assertEquals(-4, terminal.getTopRow());

        ReflectionHelpers.setField(activity, "mIsVisible", false);
        output(session, "\033[?2026hnext\r\n");
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        activity.getTermuxTerminalSessionClient().onStart();
        assertEquals(-4, terminal.getTopRow());
        output(session, "\033[?2026l");
        assertEquals(-5, terminal.getTopRow());
        activity.getTermuxTerminalSessionClient().onStart();
        assertEquals(-5, terminal.getTopRow());
        output(session, "next\r\n");
        assertEquals(-6, terminal.getTopRow());
    }

    private void assertForegroundSelectionAfterOutput(boolean scrollLocked, boolean selectOldest) throws InterruptedException {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = notificationActivity(controller);
        controller.visible();
        size(activity.getTerminalPanes(), 900, 1000);
        TerminalSession session = session("Agent");
        session.updateTerminalSessionClient(activity.getTermuxTerminalSessionClient());
        activity.getTerminalPanes().showSession(session);
        output(session, lines(180));
        TerminalView terminal = activity.getTerminalView();
        int history = session.getEmulator().getScreen().getActiveTranscriptRows();
        assertEquals("The transcript must be full before selection", 100 - session.getEmulator().mRows, history);
        assertTrue(history > 3);
        int top = selectOldest ? -history : -3;
        terminal.setTopRow(top);
        if (scrollLocked) scrollKey(activity).onTerminalExtraKeyButtonClick(null, "SCROLL", false, false, false, false);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            6 * terminal.mRenderer.getFontWidth(), terminal.getPointY(top + 1) - 1, 0);
        terminal.startTextSelectionMode(press);
        press.recycle();
        assertTrue("Enter selection through the real view/controller", terminal.isSelectingText());
        String selected = terminal.getSelectedText();
        assertNotNull(selected);
        assertFalse(selected.isEmpty());
        // The selection controller uses wall time, not Robolectric's virtual looper clock.
        Thread.sleep(350);

        ReflectionHelpers.setField(activity, "mIsVisible", false);
        output(session, "background\r\n");
        assertTrue(terminal.isSelectingText());
        assertEquals(top, terminal.getTopRow());
        assertEquals(history, session.getEmulator().getScreen().getActiveTranscriptRows());
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        activity.getTermuxTerminalSessionClient().onStart();
        if (selectOldest) {
            assertFalse("Evicted selection must be cancelled", terminal.isSelectingText());
            assertEquals(scrollLocked ? -history : 0, terminal.getTopRow());
        } else {
            assertTrue(terminal.isSelectingText());
            assertEquals(-4, terminal.getTopRow());
            assertEquals("Retained selection must follow its text, not the viewport", selected, terminal.getSelectedText());
        }
        terminal.stopTextSelectionMode();
    }

    @Test
    public void foregroundSelectionEvictionRecoversToBottom() throws InterruptedException {
        assertForegroundSelectionAfterOutput(false, true);
    }

    @Test
    public void foregroundSelectionEvictionRespectsScrollLock() throws InterruptedException {
        assertForegroundSelectionAfterOutput(true, true);
    }

    @Test
    public void foregroundRetainedSelectionStaysAnchored() throws InterruptedException {
        assertForegroundSelectionAfterOutput(false, false);
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
        assertTrue("Selecting an existing pane must move keyboard focus", panes.getActiveTerminal().hasFocus());
        assertSame(a, first.getCurrentSession());
        panes.setFontSize(panes.getActiveTerminal(), 24);
        assertEquals(16, panes.getFontSize(first));
        assertEquals(24, panes.getFontSize(panes.getActiveTerminal()));
        activity.finish();
    }

    @Test
    public void delayedTapCannotStealFocusFromTheNewPane() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout panes = layout(activity);
        panes.showSession(session("Agent"));
        panes.openBeside(session("Tests"));
        size(panes, 900, 1000);
        TerminalView first = panes.getTerminals()[0], second = panes.getTerminals()[1];
        long time = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10, 10, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_UP, 10, 10, 0);
        first.dispatchTouchEvent(down);
        first.dispatchTouchEvent(up);
        second.dispatchTouchEvent(down);
        down.recycle();
        up.recycle();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));
        assertSame(second, panes.getActiveTerminal());
        assertTrue("An older pane's delayed click must not regain keyboard focus", second.hasFocus());
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
        history(a);
        history(b);
        panes.getTerminals()[0].setTopRow(-3);
        panes.getTerminals()[1].setTopRow(-6);
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
        assertEquals(panes.getHeight(), ((View) panes.getActiveTerminal().getParent()).getHeight());
        assertEquals(-6, panes.getActiveTerminal().getTopRow());
        capture(panes, "split-cover");
        size(panes, 900, 1000);
        assertTrue(panes.isShowingBoth());
        assertEquals(LinearLayout.VERTICAL, panes.getOrientation());
        assertEquals(0.35f, panes.getFraction(), 0.001f);
        panes.toggleMaximize();
        assertFalse(panes.isShowingBoth());
        panes.toggleMaximize();
        size(panes, 900, 1000);
        assertTrue(panes.isShowingBoth());
        assertEquals(-3, panes.getTerminals()[0].getTopRow());
        assertEquals(-6, panes.getTerminals()[1].getTopRow());
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
        size(panes, 900, 1000);
        history(a);
        history(b);
        panes.getTerminals()[0].setTopRow(-3);
        panes.getTerminals()[1].setTopRow(-6);
        Bundle state = panes.saveState();
        activity.finish();
        Activity replacement = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalPaneLayout restored = unmeasuredLayout(replacement);
        restored.restoreState(state, handle -> a.mHandle.equals(handle) ? a : b.mHandle.equals(handle) ? b : null);
        assertNull(restored.getActiveTerminal().mEmulator);
        size(restored, 900, 1000);
        assertNotSame(panes.getActiveTerminal(), restored.getActiveTerminal());
        assertEquals(-3, restored.getTerminals()[0].getTopRow());
        assertEquals(-6, restored.getTerminals()[1].getTopRow());
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
        replacement.finish();
    }
}
