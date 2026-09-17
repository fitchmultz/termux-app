package com.termux.app.terminal;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = EvidenceDockTest.TestApplication.class,
    qualifiers = "w900dp-h1000dp-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
public class FoldControlsTest {
    private TermuxActivity activity() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxTerminalViewAndClients");
        ReflectionHelpers.callInstanceMethod(activity, "setTerminalToolbarView", ClassParameter.from(Bundle.class, null));
        activity.getTerminalToolbarContainer().setVisibility(View.VISIBLE);
        return activity;
    }

    private TerminalSession session(String name) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, client));
        ReflectionHelpers.setField(session, "mShellPid", 1234);
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
        assertEquals("Dock", dock.getText().toString());
        assertEquals("Panes", options.getText().toString());
        assertEquals(android.graphics.Color.WHITE, dock.getCurrentTextColor());
        assertEquals(android.graphics.Color.WHITE, options.getCurrentTextColor());
        assertTrue(dock.getHeight() >= 48);
        assertTrue(options.getWidth() >= 48);
        assertEquals("Single-pane terminal has no duplicate header", 2, panes.getActiveTerminal().getTop());
        assertEquals(48, panes.getTop());
        ExtraKeysView keys = activity.getExtraKeysView();
        assertNotNull(keys);
        assertEquals(1, keys.getRowCount());
        assertEquals(48, activity.getTerminalToolbarViewPager().getHeight());
        for (int i = 0; i < keys.getChildCount(); i++) {
            assertTrue(keys.getChildAt(i).getWidth() >= 48);
            assertTrue(keys.getChildAt(i).getHeight() >= 48);
        }
        View ctrl = keys.getChildAt(2);
        ctrl.performClick();
        assertTrue(keys.readSpecialButton(SpecialButton.CTRL, false));
        assertEquals("↑", ((TextView) keys.getChildAt(8)).getText().toString());
        assertEquals("↓", ((TextView) keys.getChildAt(9)).getText().toString());
        TerminalPaneLayoutTest.capture(root, "controls-wide-" + (activity.getResources().getConfiguration().uiMode & 48));
        TerminalPaneLayoutTest.size(root, 400, 450);
        assertEquals(2, keys.getRowCount());
        assertEquals(96, activity.getTerminalToolbarViewPager().getHeight());
        assertSame(ctrl, keys.getChildAt(2));
        assertTrue(keys.readSpecialButton(SpecialButton.CTRL, false));
        assertSame(first, panes.getActiveTerminal().getCurrentSession());
        TerminalPaneLayoutTest.capture(root, "controls-narrow-" + (activity.getResources().getConfiguration().uiMode & 48));
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertEquals(1, keys.getRowCount());
        panes.openBeside(second);
        panes.setSplitOrientation(LinearLayout.HORIZONTAL);
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertTrue(panes.isShowingBoth());
        assertEquals(50, panes.getActiveTerminal().getTop());
        panes.toggleMaximize();
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertEquals(2, panes.getActiveTerminal().getTop());
        assertSame(second, panes.getActiveTerminal().getCurrentSession());
        panes.toggleMaximize();
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertTrue(panes.isShowingBoth());
    }

    @Test @Config(qualifiers = "w900dp-h1000dp-mdpi-notnight")
    public void lightControlsStayReadableAndReflowWithoutLosingState() throws Exception { checkControls(); }

    @Test @Config(qualifiers = "w900dp-h1000dp-mdpi-night")
    public void darkControlsStayReadableAndReflowWithoutLosingState() throws Exception { checkControls(); }

    @Test
    public void largeLabelsKeepTwoRows() {
        TermuxActivity activity = activity();
        View root = activity.findViewById(R.id.activity_termux_root_view);
        TerminalPaneLayoutTest.size(root, 900, 650);
        ExtraKeysView keys = activity.getExtraKeysView();
        for (int i = 0; i < keys.getChildCount(); i++) ((TextView) keys.getChildAt(i)).setTextSize(32);
        TerminalPaneLayoutTest.size(root, 900, 650);
        assertEquals(2, keys.getRowCount());
        assertEquals(96, activity.getTerminalToolbarViewPager().getHeight());
    }
}
