package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
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
import org.robolectric.shadows.ShadowPaint;
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
        static final List<View> restarts = new ArrayList<>();

        @Implementation protected void restartInput(View view) { restarts.add(view); }

        /**
         * Provide a deterministic test-only bridge for this hidden IMM method. Keep the
         * BaseInputConnection -> IMM boundary real, then model only the asynchronous dispatch to
         * the supplied view. No PTY bytes are fabricated; the real TerminalView handles each key.
         * This does not emulate Android's served-view fallback or ViewRootImpl routing.
         */
        @Implementation protected void dispatchKeyEventFromInputMethod(View target, KeyEvent event) {
            new Handler(Looper.getMainLooper()).post(() -> target.dispatchKeyEvent(event));
        }

        @Implementation protected void updateSelection(View view, int start, int end, int composingStart, int composingEnd) {
            selections.add(view);
        }
        @Implementation protected void updateExtractedText(View view, int token, ExtractedText text) {
            extractedTokens.add(token);
            assertEquals("xx", text.text.toString());
            assertEquals(1, text.selectionStart);
            assertEquals(1, text.selectionEnd);
        }
        static void clear() { selections.clear(); extractedTokens.clear(); restarts.clear(); }
    }

    /**
     * Layout-only API24 geometry: Robolectric 4.17 ShadowPaint stores text size but has no
     * getFontSpacing implementation. Use its inherited getter, not renderer-field forcing.
     * This preserves the real attached/focused input graph without claiming pixel/font fidelity.
     */
    @Implements(value = Paint.class, minSdk = 24, maxSdk = 24)
    public static class Api24LayoutPaint extends ShadowPaint {
        @Implementation protected float getFontSpacing() { return getTextSize(); }
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
        TestSessions.markShell(result, 1234);
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
        assertEquals("IMM key dispatch must remain asynchronous", "", received(session));
        idle();
        assertEquals("\u007f\u007f", received(session));
        assertEquals("", received(otherSession));
    }

    @Test
    @Config(sdk = 24, shadows = {TerminalImePasteTest.NoPty.class, RecordingImm.class, Api24LayoutPaint.class})
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void virtualBoundariesAlsoWorkAtTheMinimumSdkWithoutInitialSurroundingApi() {
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        assertEquals("x", input.getTextBeforeCursor(1, 0).toString());
        assertEquals("x", input.getTextAfterCursor(1, 0).toString());
        input.commitText("ok", 1);
        assertEquals("ok", received(session));
        input.deleteSurroundingText(1, 0);
        assertEquals("", received(session));
        idle();
        assertEquals("\u007f", received(session));
    }

    @Test public void focusedSessionNavigationRestartsHoneyboardWithoutShowingTheKeyboard() {
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        monitor(old, 41);
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());
        RecordingImm.clear();
        assertTrue(terminal.attachSession(otherSession));
        assertTrue(terminal.hasFocus());
        idle();
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(terminal, RecordingImm.restarts.get(0));
        assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());
        assertTrue(RecordingImm.selections.isEmpty());
        assertTrue(RecordingImm.extractedTokens.isEmpty());

        // restartInput is recorded, not a fake framework rebind. Supply Android's new callback.
        EditorInfo attrs = new EditorInfo();
        InputConnection current = terminal.onCreateInputConnection(attrs);
        old.closeConnection();
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
        assertEquals(1, attrs.initialSelStart);
        assertEquals(1, attrs.initialSelEnd);
        assertEquals("x", current.getTextBeforeCursor(1, 0).toString());
        assertEquals("x", current.getTextAfterCursor(1, 0).toString());
        monitor(current, 42);
        current.commitText("new target", 1);
        idle();
        assertTrue(RecordingImm.extractedTokens.contains(42));
        assertFalse(RecordingImm.extractedTokens.contains(41));
        assertEquals("", received(session));
        assertEquals("new target", received(otherSession));
    }

    @Test public void nullAndRapidSessionReplacementsRestartOnlyTheFinalFocusedDestination() {
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        RecordingImm.clear();
        terminal.attachSession(null);
        terminal.attachSession(otherSession);
        terminal.attachSession(session);
        idle();
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(terminal, RecordingImm.restarts.get(0));
        assertSame(session, terminal.getCurrentSession());
        old.closeConnection();
        assertEquals("", received(session));
        assertEquals("", received(otherSession));

        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        assertEquals("x", current.getTextAfterCursor(1, 0).toString());
        current.commitText("final target", 1);
        assertEquals("final target", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void queuedSessionRestartCannotStealPaneOrNativeEditorFocus() {
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        RecordingImm.clear();
        terminal.attachSession(otherSession);
        assertTrue(other.requestFocus());
        idle();
        assertTrue(RecordingImm.restarts.isEmpty());
        old.closeConnection();

        InputConnection otherInput = other.onCreateInputConnection(new EditorInfo());
        otherInput.setComposingText("other draft", 1);
        other.attachSession(session);
        assertTrue(editor.requestFocus());
        InputConnection nativeInput = editor.onCreateInputConnection(new EditorInfo());
        nativeInput.setComposingText("native draft", 1);
        idle();
        assertTrue(RecordingImm.restarts.isEmpty());
        otherInput.closeConnection();
        assertEquals("native draft", editor.getText().toString());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void nonHoneyboardSessionNavigationKeepsLegacyRestartAndCloseBehavior() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.keyboard/.Ime");
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("normal close", 1);
        input.closeConnection();
        assertEquals("normal close", received(session));
        input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("legacy draft", 1);
        RecordingImm.clear();
        terminal.attachSession(otherSession);
        idle();
        assertTrue(RecordingImm.restarts.isEmpty());
        assertEquals("legacy draft", input.getTextBeforeCursor(100, 0).toString());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
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
