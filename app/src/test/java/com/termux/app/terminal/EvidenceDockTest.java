package com.termux.app.terminal;

import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.EditText;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxApplication;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = EvidenceDockTest.TestApplication.class, shadows = TerminalPaneLayoutTest.NoPty.class)
public class EvidenceDockTest {
    public static class TestApplication extends TermuxApplication {
        @Override public void onCreate() { }
    }

    private TerminalSession session(String name) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, client));
        ReflectionHelpers.setField(session, "mShellPid", 1234);
        session.mSessionName = name;
        return session;
    }

    private String input(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read", ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    @Test
    public void draftsRoutePerSessionAndOnlyExplicitControlsWriteBracketedInput() throws Exception {
        // Attach real app views without starting Termux's process/service lifecycle.
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        TerminalPaneLayout panes = activity.findViewById(R.id.terminal_panes);
        ReflectionHelpers.setField(activity, "mTerminalPanes", panes);
        for (TerminalView terminal : panes.getTerminals()) {
            terminal.setTerminalViewClient(new TermuxTerminalViewClientBase());
            panes.setFontSize(terminal, 16);
        }
        EvidenceDock dock = new EvidenceDock(activity);
        ReflectionHelpers.setField(activity, "mEvidenceDock", dock);
        panes.setOnActiveChanged(view -> ReflectionHelpers.callInstanceMethod(activity, "onTerminalPaneChanged", ClassParameter.from(TerminalView.class, view)));
        TerminalSession a = session("Agent"), b = session("Tests");
        panes.showSession(a);
        EditText editor = activity.findViewById(R.id.terminal_toolbar_stacked_text_input);
        editor.setText("line one\nline two");
        assertEquals("", input(a));
        activity.findViewById(R.id.evidence_insert).performClick();
        assertEquals("", input(a));
        assertEquals("line one\nline two", editor.getText().toString());
        byte[] enable = "\033[?2004h".getBytes(StandardCharsets.UTF_8);
        a.getEmulator().append(enable, enable.length);
        activity.findViewById(R.id.evidence_insert).performClick();
        assertEquals("\033[200~line one\rline two\033[201~", input(a));
        assertEquals("", editor.getText().toString());
        editor.setText("draft A");
        panes.openBeside(b);
        assertEquals("", editor.getText().toString());
        editor.setText("draft B");
        panes.showSession(a);
        assertEquals("draft A", editor.getText().toString());
        activity.findViewById(R.id.evidence_send).performClick();
        assertEquals("", input(a));
        android.app.AlertDialog staleConfirmation = ShadowAlertDialog.getLatestAlertDialog();
        panes.showSession(b);
        staleConfirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("", input(a));
        assertEquals("", input(b));
        panes.showSession(a);
        activity.findViewById(R.id.evidence_send).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("\033[200~draft A\033[201~\r", input(a));
        assertEquals("", input(b));
        panes.showSession(b);
        assertEquals("draft B", editor.getText().toString());
        activity.showEvidenceDock();
        panes.setSplitOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.view.View root = activity.findViewById(R.id.activity_termux_root_view);
        TerminalPaneLayoutTest.size(root, 900, 1000);
        TerminalPaneLayoutTest.capture(root, "evidence-wide");
        TerminalPaneLayoutTest.size(root, 400, 900);
        TerminalPaneLayoutTest.capture(root, "evidence-cover");
        dock.dispose();
    }

    @Test
    public void staleActivitySaveCannotOverwriteAnImportResult() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        TerminalPaneLayout panes = activity.findViewById(R.id.terminal_panes);
        ReflectionHelpers.setField(activity, "mTerminalPanes", panes);
        for (TerminalView terminal : panes.getTerminals()) terminal.setTerminalViewClient(new TermuxTerminalViewClientBase());
        EvidenceDock dock = new EvidenceDock(activity);
        TerminalSession target = session("Agent");
        dock.setSession(target);
        EditText editor = activity.findViewById(R.id.terminal_toolbar_stacked_text_input);
        editor.setText("original");
        dock.onStop();
        EvidenceStore store = ((TermuxApplication) activity.getApplication()).getEvidenceStore();
        store.get(target.mHandle).text = "original\nimported evidence";
        store.save(target.mHandle);
        dock.saveState(new Bundle());
        EvidenceStore reloaded = new EvidenceStore(activity);
        assertEquals("original\nimported evidence", reloaded.get(target.mHandle).text);
        dock.dispose();
    }
}
