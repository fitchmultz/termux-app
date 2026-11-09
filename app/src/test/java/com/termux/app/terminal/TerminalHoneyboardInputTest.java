package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowInputMethodManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Real terminal connections and an independent native EditText, without a child PTY. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class,
    shadows = {TerminalImePasteTest.NoPty.class, TerminalHoneyboardInputTest.RecordingImm.class})
public class TerminalHoneyboardInputTest {
    @Implements(InputMethodManager.class)
    public static class RecordingImm extends ShadowInputMethodManager {
        static final List<View> selections = new ArrayList<>();
        static final List<Integer> extractedTokens = new ArrayList<>();

        @Implementation protected void updateSelection(View view, int start, int end, int composingStart, int composingEnd) {
            selections.add(view);
        }
        @Implementation protected void updateExtractedText(View view, int token, ExtractedText text) {
            extractedTokens.add(token);
            assertEquals("xx", text.text.toString());
            assertEquals(1, text.selectionStart);
            assertEquals(1, text.selectionEnd);
        }
        static void clear() { selections.clear(); extractedTokens.clear(); }
    }

    private Activity activity;
    private TerminalView terminal, other;
    private TerminalSession session, otherSession;
    private EditText editor;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.samsung.android.honeyboard/.service.HoneyBoardService");
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        editor = new EditText(activity);
        terminal = terminal();
        other = terminal();
        session = session();
        otherSession = session();
        layout.addView(terminal, new LinearLayout.LayoutParams(900, 300));
        layout.addView(other, new LinearLayout.LayoutParams(900, 300));
        layout.addView(editor, new LinearLayout.LayoutParams(900, 100));
        activity.setContentView(layout);
        terminal.attachSession(session);
        other.attachSession(otherSession);
        layout.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, 900, 700);
        assertTrue(terminal.requestFocus());
        idle();
        RecordingImm.clear();
    }

    private TerminalView terminal() {
        TerminalView view = new TerminalView(activity, null);
        view.setFocusableInTouchMode(true);
        view.setTextSize(16);
        view.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override public boolean shouldEnforceCharBasedInput() { return true; }
            @Override public boolean isTerminalViewSelected() { return !editor.hasFocus(); }
        });
        return view;
    }

    private TerminalSession session() {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession result = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(result, "mEmulator", new TerminalEmulator(result, 80, 24, 8, 16, 100, client));
        ReflectionHelpers.setField(result, "mShellPid", 1234);
        return result;
    }

    private String received(TerminalSession target) {
        Object queue = ReflectionHelpers.getField(target, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void monitor(InputConnection input, int token) {
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.token = token;
        ExtractedText text = input.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
        assertNotNull(text);
        assertEquals("xx", text.text.toString());
        assertEquals(1, text.selectionStart);
        assertEquals(1, text.selectionEnd);
    }

    @Test public void virtualBoundariesAreMetadataAndPreserveSamsungInputFlags() {
        EditorInfo attrs = new EditorInfo();
        InputConnection input = terminal.onCreateInputConnection(attrs);
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, attrs.inputType);
        assertEquals(1, attrs.initialSelStart);
        assertEquals(1, attrs.initialSelEnd);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("x", attrs.getInitialTextBeforeCursor(1, 0).toString());
            assertEquals("x", attrs.getInitialTextAfterCursor(1, 0).toString());
        }
        assertEquals("x", input.getTextBeforeCursor(100, 0).toString());
        assertEquals("x", input.getTextAfterCursor(100, 0).toString());
        assertEquals("", input.getTextBeforeCursor(0, 0).toString());
        assertNull(input.getSelectedText(0));
        monitor(input, 17);
        input.commitText("hello", 1);
        assertEquals("hello", received(session));
        idle();
        assertTrue(RecordingImm.selections.contains(terminal));
        assertTrue(RecordingImm.extractedTokens.contains(17));
        input.setComposingText("한", 1);
        input.finishComposingText();
        assertEquals("한", received(session));
        input.deleteSurroundingText(2, 0);
        assertEquals("\u007f\u007f", received(session));
        assertEquals("", received(otherSession));
    }

    @Test @Config(sdk = 24) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void virtualBoundariesAlsoWorkAtTheMinimumSdkWithoutInitialSurroundingApi() {
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        assertEquals("x", input.getTextBeforeCursor(1, 0).toString());
        assertEquals("x", input.getTextAfterCursor(1, 0).toString());
        input.commitText("ok", 1);
        assertEquals("ok", received(session));
    }

    @Test public void otherInputMethodsRetainRealEditableSelectionAndComposition() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.keyboard/.Ime");
        EditorInfo attrs = new EditorInfo();
        InputConnection input = terminal.onCreateInputConnection(attrs);
        assertEquals(-1, attrs.initialSelStart);
        input.setComposingText("ab", 1);
        input.setSelection(1, 1);
        assertEquals("a", input.getTextBeforeCursor(10, 0).toString());
        assertEquals("b", input.getTextAfterCursor(10, 0).toString());
        input.finishComposingText();
        assertEquals("ab", received(session));
        idle();
        assertFalse(RecordingImm.selections.contains(terminal));
    }

    @Test public void replacedClosedAndReattachedConnectionsCannotPublishQueuedMetadata() {
        InputConnection first = terminal.onCreateInputConnection(new EditorInfo());
        monitor(first, 11);
        first.commitText("a", 1);
        InputConnection second = terminal.onCreateInputConnection(new EditorInfo());
        monitor(second, 22);
        second.commitText("b", 1);
        second.closeConnection();
        InputConnection third = terminal.onCreateInputConnection(new EditorInfo());
        monitor(third, 33);
        third.commitText("c", 1);
        terminal.attachSession(otherSession);
        idle();
        assertFalse(RecordingImm.selections.contains(terminal));
        assertTrue(RecordingImm.extractedTokens.isEmpty());
        assertEquals("abc", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void paneAndNativeEditorFocusDoNotReuseAnOldTerminalVirtualContext() {
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        monitor(input, 44);
        input.commitText("one", 1);
        assertTrue(other.requestFocus());
        idle();
        assertFalse(RecordingImm.selections.contains(terminal));
        InputConnection otherInput = other.onCreateInputConnection(new EditorInfo());
        assertEquals("x", otherInput.getTextAfterCursor(1, 0).toString());
        otherInput.commitText("two", 1);
        assertTrue(editor.requestFocus());
        idle();
        assertFalse(RecordingImm.selections.contains(other));
        EditorInfo ignoredTerminalAttrs = new EditorInfo();
        InputConnection unselected = other.onCreateInputConnection(ignoredTerminalAttrs);
        assertEquals(-1, ignoredTerminalAttrs.initialSelStart);
        assertEquals("", unselected.getTextAfterCursor(1, 0).toString());
        InputConnection editorInput = editor.onCreateInputConnection(new EditorInfo());
        assertNotNull(editorInput);
        editorInput.setComposingText("native draft", 1);
        editorInput.finishComposingText();
        assertEquals("native draft", editor.getText().toString());
        assertEquals("one", received(session));
        assertEquals("two", received(otherSession));
    }
}
