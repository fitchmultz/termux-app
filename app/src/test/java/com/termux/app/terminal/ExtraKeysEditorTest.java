package com.termux.app.terminal;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.termux.app.activities.ExtraKeysActivity;
import com.termux.app.terminal.io.ExtraKeysLayout;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = EvidenceDockTest.TestApplication.class, qualifiers = "w400dp-h800dp-mdpi")
public class ExtraKeysEditorTest {
    private List<TextView> text(View view, String value) {
        List<TextView> found = new ArrayList<>();
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) found.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(text(group.getChildAt(i), value));
        }
        return found;
    }

    private View root(ExtraKeysActivity activity) { return activity.findViewById(android.R.id.content); }
    private void tap(ExtraKeysActivity activity, String label) { text(root(activity), label).get(0).performClick(); }
    private AlertDialog dialog() { return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private void choose(String label) {
        AlertDialog dialog = dialog();
        android.widget.ListView list = dialog.getListView();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (label.equals(list.getAdapter().getItem(i).toString())) {
                list.performItemClick(null, i, i);
                return;
            }
        }
        fail("Missing choice " + label);
    }

    private List<EditText> fields(View view) {
        List<EditText> found = new ArrayList<>();
        if (view instanceof EditText) found.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(fields(group.getChildAt(i)));
        }
        return found;
    }

    @Test
    public void editsSurviveRecreationAndSaveWithoutLosingPopupActions() throws Exception {
        ActivityController<ExtraKeysActivity> controller = Robolectric.buildActivity(ExtraKeysActivity.class).setup();
        ExtraKeysActivity activity = controller.get();
        tap(activity, "CTRL");
        choose("Move right");
        tap(activity, "New session");
        choose("Remove key");
        text(root(activity), "Add key").get(1).performClick();
        choose("HOME");
        assertNull("Drafts are not saved implicitly", ExtraKeysLayout.saved(activity));
        controller.recreate();
        activity = controller.get();
        View decor = activity.getWindow().getDecorView();
        TerminalPaneLayoutTest.size(decor, 400, 800);
        TerminalPaneLayoutTest.capture(decor, "controls-editor");
        tap(activity, "Save");
        ExtraKeyButton[][] keys = new ExtraKeysInfo(ExtraKeysLayout.saved(activity), "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix();
        assertEquals("ALT", keys[0][0].getKey());
        assertEquals("CTRL", keys[0][1].getKey());
        assertEquals("CTRL ALT UP", keys[0][1].getPopup().getKey());
        assertEquals("HOME", keys[1][keys[1].length - 1].getKey());
        for (ExtraKeyButton key : keys[1]) assertNotEquals(ExtraKeysConstants.ACTION_NEW_SESSION, key.getKey());
        controller.pause().stop().destroy();

        controller = Robolectric.buildActivity(ExtraKeysActivity.class).setup();
        activity = controller.get();
        assertEquals("HOME", text(root(activity), "HOME").get(0).getText().toString());
        tap(activity, "Reset…");
        choose("Use termux.properties layout");
        tap(activity, "Cancel");
        assertNotNull("Cancel does not clear the saved layout", ExtraKeysLayout.saved(activity));
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(ExtraKeysActivity.class).setup();
        activity = controller.get();
        tap(activity, "Reset…");
        choose("Use termux.properties layout");
        tap(activity, "Save");
        assertNull(ExtraKeysLayout.saved(activity));
        controller.pause().stop().destroy();
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-night-mdpi")
    public void customKeyTextSurvivesRotationAndCancelDoesNotSave() throws Exception {
        ActivityController<ExtraKeysActivity> controller = Robolectric.buildActivity(ExtraKeysActivity.class).setup();
        ExtraKeysActivity activity = controller.get();
        tap(activity, "Add key");
        choose("Custom key or macro…");
        List<EditText> fields = fields(dialog().getWindow().getDecorView());
        fields.get(0).setText("git status");
        fields.get(1).setText("Status");
        controller.recreate();
        activity = controller.get();
        fields = fields(dialog().getWindow().getDecorView());
        assertEquals("git status", fields.get(0).getText().toString());
        assertEquals("Status", fields.get(1).getText().toString());
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(text(root(activity), "Status").isEmpty());
        View decor = activity.getWindow().getDecorView();
        TerminalPaneLayoutTest.size(decor, 400, 800);
        TerminalPaneLayoutTest.capture(decor, "controls-editor-dark");
        tap(activity, "Cancel");
        assertNull(ExtraKeysLayout.saved(activity));
        controller.pause().stop().destroy();
    }
}
