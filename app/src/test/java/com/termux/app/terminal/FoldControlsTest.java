package com.termux.app.terminal;

import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = EvidenceDockTest.TestApplication.class,
    qualifiers = "w900dp-h1000dp-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
public class FoldControlsTest {
    private TermuxActivity activity() {
        org.robolectric.android.controller.ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxTerminalViewAndClients");
        ReflectionHelpers.callInstanceMethod(activity, "setTerminalToolbarView", ClassParameter.from(Bundle.class, null));
        activity.getTerminalToolbarContainer().setVisibility(View.VISIBLE);
        View drawer = activity.findViewById(R.id.terminal_sessions_drawer);
        ((androidx.drawerlayout.widget.DrawerLayout.LayoutParams) drawer.getLayoutParams()).gravity = activity.getTerminalSessionDrawerGravity();
        controller.visible();
        return activity;
    }

    private TerminalSession session(String name) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, client));
        TestSessions.markShell(session, 1234);
        session.mSessionName = name;
        return session;
    }

    private void checkControls() throws Exception {
        TermuxActivity activity = activity();
        View root = activity.findViewById(R.id.activity_termux_root_view);
        TerminalPaneLayout panes = activity.getTerminalPanes();
        // Session routing is covered separately; this test does not start Android services.
        panes.setOnActiveChanged(null);
        TerminalSession first = session("Agent"), second = session("Tests");
        panes.showSession(first);
        TerminalPaneLayoutTest.size(root, 900, 650);
        TextView dock = activity.findViewById(R.id.evidence_dock_toggle);
        TextView options = activity.findViewById(R.id.terminal_pane_options);
        assertEquals("Compose", dock.getText().toString());
        assertEquals("Split", options.getText().toString());
        assertEquals(android.graphics.Color.WHITE, dock.getCurrentTextColor());
        assertEquals(android.graphics.Color.WHITE, options.getCurrentTextColor());
        assertTrue(dock.getHeight() >= 48);
        assertTrue(options.getWidth() >= 48);
        assertEquals("Single-pane terminal has no duplicate header or focus border", 0, panes.getActiveTerminal().getTop());
        assertEquals(32, panes.getTop());
        ExtraKeysView keys = activity.getExtraKeysView();
        assertNotNull(keys);
        assertEquals(2, keys.getRowCount());
        assertEquals(96, activity.getTerminalToolbarViewPager().getHeight());
        for (int i = 0; i < keys.getChildCount(); i++) {
            assertTrue(keys.getChildAt(i).getWidth() >= 48);
            assertTrue(keys.getChildAt(i).getHeight() >= 48);
        }
        View ctrl = keys.getChildAt(0);
        int rightInset = keys.getWidth() - keys.getChildAt(5).getRight();
        int keyWidth = keys.getChildAt(5).getWidth();
        assertTrue("Wide screens keep controls near the right hand", keys.getChildAt(0).getLeft() > keys.getWidth() / 2);
        ctrl.performClick();
        assertTrue(keys.readSpecialButton(SpecialButton.CTRL, false));
        assertEquals("↑", ((TextView) keys.getChildAt(2)).getText().toString());
        assertEquals("↓", ((TextView) keys.getChildAt(8)).getText().toString());
        TerminalPaneLayoutTest.capture(root, "controls-wide-" + (activity.getResources().getConfiguration().uiMode & 48));
        TerminalPaneLayoutTest.size(root, 400, 450);
        assertEquals(2, keys.getRowCount());
        assertEquals(96, activity.getTerminalToolbarViewPager().getHeight());
        assertSame(ctrl, keys.getChildAt(0));
        assertEquals(rightInset, keys.getWidth() - keys.getChildAt(5).getRight());
        assertEquals(keyWidth, keys.getChildAt(5).getWidth());
        assertEquals("TAB", ((TextView) keys.getChildAt(4)).getText().toString());
        assertEquals("Next session", keys.getChildAt(10).getContentDescription());
        assertEquals("Sessions", keys.getChildAt(5).getContentDescription());
        assertEquals("New session", keys.getChildAt(6).getContentDescription());
        assertTrue(keys.readSpecialButton(SpecialButton.CTRL, false));
        assertSame(first, panes.getActiveTerminal().getCurrentSession());
        TerminalPaneLayoutTest.capture(root, "controls-narrow-" + (activity.getResources().getConfiguration().uiMode & 48));
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertEquals(2, keys.getRowCount());
        activity.getDrawer().openDrawer(activity.getTerminalSessionDrawerGravity(), false);
        TerminalPaneLayoutTest.size(root, 900, 650);
        TerminalPaneLayoutTest.capture(root, "controls-sessions-" + (activity.getResources().getConfiguration().uiMode & 48));
        activity.getDrawer().closeDrawers();
        panes.openBeside(second);
        panes.setSplitOrientation(LinearLayout.HORIZONTAL);
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertTrue(panes.isShowingBoth());
        assertEquals(50, panes.getActiveTerminal().getTop());
        panes.toggleMaximize();
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertEquals(0, panes.getActiveTerminal().getTop());
        assertSame(second, panes.getActiveTerminal().getCurrentSession());
        panes.toggleMaximize();
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertTrue(panes.isShowingBoth());
    }

    @Test @Config(qualifiers = "w900dp-h1000dp-notnight-mdpi")
    public void lightControlsStayReachableWithoutLosingState() throws Exception { checkControls(); }

    @Test @Config(qualifiers = "w900dp-h1000dp-night-mdpi")
    public void darkControlsStayReachableWithoutLosingState() throws Exception { checkControls(); }

    @Test
    public void largeLabelsExpandTheClusterWithoutClipping() throws Exception {
        TermuxActivity activity = activity();
        View root = activity.findViewById(R.id.activity_termux_root_view);
        TerminalPaneLayoutTest.size(root, 900, 650);
        ExtraKeysView keys = activity.getExtraKeysView();
        for (int i = 0; i < keys.getChildCount(); i++) ((TextView) keys.getChildAt(i)).setTextSize(32);
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertEquals(2, keys.getRowCount());
        assertEquals(96, activity.getTerminalToolbarViewPager().getHeight());
        for (int i = 0; i < keys.getChildCount(); i++) {
            TextView key = (TextView) keys.getChildAt(i);
            assertTrue(key.getWidth() >= key.getPaint().measureText(key.getText().toString()) + 16);
        }
        TerminalPaneLayoutTest.capture(root, "controls-large-labels");
        TerminalPaneLayoutTest.size(root, 400, 650);
        android.widget.HorizontalScrollView scroll = (android.widget.HorizontalScrollView) keys.getParent();
        assertTrue("Oversized rows scroll instead of shrinking targets", keys.getWidth() > scroll.getWidth());
        scroll.scrollTo(keys.getWidth(), 0);
        assertTrue(scroll.getScrollX() > 0);
        assertTrue(keys.getChildAt(keys.getChildCount() - 1).getRight() - scroll.getScrollX() <= scroll.getWidth());
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    public void customMatricesKeepTheirRowsLabelsAndFullWidth() throws Exception {
        TermuxActivity activity = activity();
        View root = activity.findViewById(R.id.activity_termux_root_view);
        TerminalPaneLayoutTest.size(root, 900, 650);
        ExtraKeysView keys = activity.getExtraKeysView();
        keys.setAdaptiveRows(false);
        keys.reload(new com.termux.shared.termux.extrakeys.ExtraKeysInfo(
            "[[ESC, TAB], [LEFT, RIGHT]]", "default",
            com.termux.shared.termux.extrakeys.ExtraKeysConstants.CONTROL_CHARS_ALIASES), 96);
        for (int width : new int[]{900, 400, 900}) {
            TerminalPaneLayoutTest.size(root, width, 650);
            assertEquals(2, keys.getRowCount());
            assertEquals(0, keys.getPaddingLeft());
            assertEquals("↹", ((TextView) keys.getChildAt(1)).getText().toString());
            assertTrue(keys.getChildAt(0).getWidth() >= (keys.getWidth() - 1) / 2);
        }

        ExtraKeysInfo modifiers = new ExtraKeysInfo("[[CTRL, LEFT]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        keys.reload(modifiers, 96);
        View ctrl = keys.getChildAt(0);
        touch(ctrl, MotionEvent.ACTION_DOWN, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressTimeout()));
        touch(ctrl, MotionEvent.ACTION_UP, 1);
        assertTrue(keys.readSpecialButton(SpecialButton.CTRL, true));
        keys.reload(modifiers, 96);
        assertTrue("A modifier still in the layout keeps its lock", keys.readSpecialButton(SpecialButton.CTRL, true));
        keys.reload(new ExtraKeysInfo("[[{key: LEFT, popup: CTRL}]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES), 96);
        assertTrue("A modifier available through a popup keeps its lock", keys.readSpecialButton(SpecialButton.CTRL, true));
        keys.reload(new ExtraKeysInfo("[[LEFT, RIGHT]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES), 96);
        assertFalse("A removed modifier cannot affect terminal input", keys.readSpecialButton(SpecialButton.CTRL, true));
        keys.reload(modifiers, 96);
        assertFalse("Re-adding a modifier cannot revive its old lock", keys.readSpecialButton(SpecialButton.CTRL, true));
    }

    private static void touch(View view, int action, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 1, y, 0);
        try {
            assertTrue(view.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    public void heldKeysRepeatOnMainAndStopOnReleaseReloadOrDetach() throws Exception {
        TermuxActivity activity = activity();
        TerminalPaneLayout panes = activity.getTerminalPanes();
        panes.setOnActiveChanged(null);
        panes.showSession(session("Keys"));
        View root = activity.findViewById(R.id.activity_termux_root_view);
        ExtraKeysView keys = activity.getExtraKeysView();
        ExtraKeysInfo layout = new ExtraKeysInfo("[[CTRL, LEFT]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        keys.reload(layout, 96);
        TerminalPaneLayoutTest.size(root, 900, 650);
        ExtraKeysView.IExtraKeysView client = keys.getExtraKeysViewClient();
        List<String> delivered = new ArrayList<>();
        keys.setExtraKeysViewClient(new ExtraKeysView.IExtraKeysView() {
            @Override
            public void onExtraKeyButtonClick(View view, ExtraKeyButton info, MaterialButton button) {
                assertSame(Looper.getMainLooper(), Looper.myLooper());
                delivered.add(info.getKey());
                client.onExtraKeyButtonClick(view, info, button);
            }

            @Override
            public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton info, MaterialButton button) {
                return true;
            }
        });
        keys.getChildAt(0).performClick();
        View left = keys.getChildAt(1);
        touch(left, MotionEvent.ACTION_DOWN, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressTimeout()));
        assertEquals(1, delivered.size());
        assertFalse("Repeated input consumes the one-shot modifier", keys.readSpecialButton(SpecialButton.CTRL, false));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressRepeatDelay()));
        assertEquals(2, delivered.size());
        touch(left, MotionEvent.ACTION_UP, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressRepeatDelay()));
        assertEquals("Release neither repeats nor clicks again", 2, delivered.size());

        touch(left, MotionEvent.ACTION_DOWN, 1);
        touch(left, MotionEvent.ACTION_CANCEL, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressTimeout()));
        assertEquals(2, delivered.size());
        touch(left, MotionEvent.ACTION_DOWN, 1);
        keys.reload(layout, 96);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressTimeout()));
        assertEquals(2, delivered.size());
        touch(keys.getChildAt(1), MotionEvent.ACTION_DOWN, 1);
        ((ViewGroup) keys.getParent()).removeView(keys);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.getLongPressTimeout()));
        assertEquals(2, delivered.size());
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    public void popupOwnershipEndsOnCancelDismissReloadOrDetach() throws Exception {
        TermuxActivity activity = activity();
        ExtraKeysView keys = activity.getExtraKeysView();
        ExtraKeysInfo layout = new ExtraKeysInfo("[[{key: LEFT, popup: HOME}]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        keys.reload(layout, 96);
        List<String> delivered = new ArrayList<>();
        keys.setExtraKeysViewClient(new ExtraKeysView.IExtraKeysView() {
            @Override
            public void onExtraKeyButtonClick(View view, ExtraKeyButton info, MaterialButton button) {
                delivered.add(info.getKey());
            }

            @Override
            public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton info, MaterialButton button) {
                return true;
            }
        });
        View root = activity.findViewById(R.id.activity_termux_root_view);
        TerminalPaneLayoutTest.size(root, 900, 650);
        View left = keys.getChildAt(0);
        touch(left, MotionEvent.ACTION_DOWN, 1);
        touch(left, MotionEvent.ACTION_MOVE, -1);
        PopupWindow popup = ReflectionHelpers.getField(keys, "mPopupWindow");
        assertTrue(popup.isShowing());
        touch(left, MotionEvent.ACTION_CANCEL, -1);
        assertFalse(popup.isShowing());
        assertNull(ReflectionHelpers.getField(keys, "mPopupWindow"));

        touch(left, MotionEvent.ACTION_DOWN, 1);
        touch(left, MotionEvent.ACTION_MOVE, -1);
        popup = ReflectionHelpers.getField(keys, "mPopupWindow");
        popup.dismiss();
        assertNull("Framework dismissal releases the reference", ReflectionHelpers.getField(keys, "mPopupWindow"));
        touch(left, MotionEvent.ACTION_CANCEL, -1);

        touch(left, MotionEvent.ACTION_DOWN, 1);
        touch(left, MotionEvent.ACTION_MOVE, -1);
        popup = ReflectionHelpers.getField(keys, "mPopupWindow");
        keys.reload(layout, 96);
        assertFalse(popup.isShowing());
        assertNull(ReflectionHelpers.getField(keys, "mPopupWindow"));
        assertTrue("Cancelled or dismissed popups never send a key", delivered.isEmpty());
        TerminalPaneLayoutTest.size(root, 900, 650);
        left = keys.getChildAt(0);
        touch(left, MotionEvent.ACTION_DOWN, 1);
        touch(left, MotionEvent.ACTION_MOVE, -1);
        touch(left, MotionEvent.ACTION_UP, -1);
        assertEquals(java.util.Collections.singletonList("HOME"), delivered);
        assertNull(ReflectionHelpers.getField(keys, "mPopupWindow"));
        touch(left, MotionEvent.ACTION_DOWN, 1);
        touch(left, MotionEvent.ACTION_MOVE, -1);
        popup = ReflectionHelpers.getField(keys, "mPopupWindow");
        ((ViewGroup) keys.getParent()).removeView(keys);
        assertFalse(popup.isShowing());
        assertNull(ReflectionHelpers.getField(keys, "mPopupWindow"));
        assertEquals("Detach cannot deliver an alternate key", java.util.Collections.singletonList("HOME"), delivered);
    }
}
