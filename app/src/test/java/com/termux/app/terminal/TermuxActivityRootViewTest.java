package com.termux.app.terminal;

import android.view.View;
import android.widget.FrameLayout;

import com.termux.app.TermuxActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 37}, application = EvidenceDockTest.TestApplication.class)
public class TermuxActivityRootViewTest {
    @Test
    public void enteringMultiWindowClearsAppliedAndPendingKeyboardMargins() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        TermuxActivityRootView root = new TermuxActivityRootView(activity);
        activity.setContentView(root);
        root.setActivity(activity);
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        View bottom = new View(activity);
        root.addView(bottom);
        ReflectionHelpers.setField(activity, "mTermuxActivityBottomSpaceView", bottom);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) root.getLayoutParams();
        params.setMargins(12, 24, 36, 120);
        root.setLayoutParams(params);
        root.marginBottom = 120;
        root.lastMarginBottom = 120;
        shadowOf(activity).setInMultiWindowMode(true);

        root.onGlobalLayout();
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        assertEquals(0, params.bottomMargin);
        assertNull(root.marginBottom);
        assertNull(root.lastMarginBottom);
        assertEquals(12, params.leftMargin);
        assertEquals(24, params.topMargin);
        assertEquals(36, params.rightMargin);

        // A resize can precede the next global-layout callback.
        root.marginBottom = 80;
        root.lastMarginBottom = 80;
        root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        assertEquals(0, params.bottomMargin);
        assertNull(root.lastMarginBottom);

        shadowOf(activity).setInMultiWindowMode(false);
        root.marginBottom = 80;
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        assertEquals("Fullscreen retains the keyboard-overlap workaround", 80, params.bottomMargin);
    }
}
