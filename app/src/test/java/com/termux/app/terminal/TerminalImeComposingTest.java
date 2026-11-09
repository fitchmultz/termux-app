package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

import org.junit.Before;
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
import org.robolectric.shadows.ShadowInputMethodManager;
import org.robolectric.shadows.ShadowPaint;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class,
    shadows = {TerminalImePasteTest.NoPty.class, TerminalImeComposingTest.RecordingImm.class})
public class TerminalImeComposingTest {
    @Implements(InputMethodManager.class)
    public static class RecordingImm extends ShadowInputMethodManager {
        static final List<View> restarts = new ArrayList<>();
        static int selectionStart, selectionEnd, composingStart, composingEnd;
        static ExtractedText extracted;
        @Implementation protected void restartInput(View view) { restarts.add(view); }

        /**
         * Deterministic test-only bridge for the hidden IMM key-dispatch method. Preserve the
         * BaseInputConnection -> IMM call and deferred timing; the real target handles each key.
         * This does not emulate served-view fallback or ViewRootImpl routing, and fabricates no PTY bytes.
         */
        @Implementation protected void dispatchKeyEventFromInputMethod(View target, KeyEvent event) {
            new Handler(Looper.getMainLooper()).post(() -> target.dispatchKeyEvent(event));
        }

        @Implementation protected void updateSelection(View view, int start, int end, int candidateStart, int candidateEnd) {
            selectionStart = start;
            selectionEnd = end;
            composingStart = candidateStart;
            composingEnd = candidateEnd;
        }
        @Implementation protected void updateExtractedText(View view, int token, ExtractedText text) { extracted = text; }
        static void clear() { restarts.clear(); extracted = null; }
    }

    /**
     * API24's legacy Paint shadow leaves float font spacing at zero. These input-only tests
     * still need a real attached, measured and focused view/session graph. Supply spacing
     * from the configured text size, not native glyph metrics or patched renderer fields.
     * Native API28/37 rendering tests never select this shadow.
     */
    @Implements(value = Paint.class, minSdk = 24, maxSdk = 24)
    public static class Api24LayoutPaint extends ShadowPaint {
        @Implementation protected float getFontSpacing() { return getTextSize(); }
    }

    private ActivityController<Activity> controller;
    private Activity activity;
    private TerminalView terminal, other;
    private TerminalSession session, otherSession;
    private EditText editor;
    private boolean enabled, enforce = true;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
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
            @Override public boolean shouldEnforceCharBasedInput() { return enforce; }
            @Override public boolean shouldEnableImeComposing() { return enabled; }
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

    private void output(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(bytes, bytes.length);
    }

    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private String preview() { return ReflectionHelpers.getField(terminal, "mComposingText"); }

    private Bitmap capture() {
        Bitmap bitmap = Bitmap.createBitmap(terminal.getWidth(), terminal.getHeight(), Bitmap.Config.ARGB_8888);
        terminal.draw(new Canvas(bitmap));
        return bitmap;
    }

    /**
     * Synthetic candidate OFF/ON evidence through the actual served InputConnection and
     * TerminalView draw path. This is neither a Fold16 baseline nor a physical-device capture.
     * The client flag is test-local; no app, shared Pi, or device preference is changed.
     */
    @Test public void captureSyntheticCandidateCjkPreeditOffAndOn() throws Exception {
        Bitmap off = captureSyntheticCandidatePreedit(false);
        Bitmap on = null;
        try {
            on = captureSyntheticCandidatePreedit(true);
            // Compare only the prompt's preedit area, not the deliberately different mode label.
            int left = (int) Math.floor(4 * terminal.mRenderer.getFontWidth());
            int top = 8 * terminal.mRenderer.getFontLineSpacing();
            int right = (int) Math.ceil(24 * terminal.mRenderer.getFontWidth());
            int bottom = top + 2 * terminal.mRenderer.getFontLineSpacing();
            boolean changedAtPrompt = false;
            for (int y = top; y < bottom && !changedAtPrompt; y++) {
                for (int x = left; x < right; x++) {
                    if (off.getPixel(x, y) != on.getPixel(x, y)) {
                        changedAtPrompt = true;
                        break;
                    }
                }
            }
            assertTrue("Opt-in preedit must change native pixels at the completed prompt", changedAtPrompt);
        } finally {
            off.recycle();
            if (on != null) on.recycle();
        }
    }

    private Bitmap captureSyntheticCandidatePreedit(boolean optIn) throws Exception {
        enabled = optIn;
        terminal.refreshImeComposingPreference();
        idle();
        assertTrue(terminal.requestFocus());
        assertTrue(terminal.hasFocus());
        terminal.setTerminalCursorBlinkerRate(0);
        terminal.setTerminalCursorBlinkerState(false, false);
        // Finish the terminal frame before requesting composition: the preview must use this
        // completed cursor/palette, not a hand-positioned string or a live partial snapshot.
        output("\033[?2026h\033[2J\033[H\033]10;#d5deeb\007\033]11;#101923\007"
            + "\033[1;36mSYNTHETIC CANDIDATE / CJK PREEDIT " + (optIn ? "ON" : "OFF") + "\033[0m\r\n"
            + "\033[90mNative TerminalView / not a physical-device capture\033[0m\r\n\r\n"
            + "\033[32m$ \033[0mpython greeting.py\r\n"
            + "\033[90m# The draft is held by the IME, not sent to the PTY.\033[0m\r\n"
            + "\033[36mprint\033[0m(\033[33m\"Choose a greeting\"\033[0m)\r\n\r\n"
            + "\033[90mWaiting for uncommitted CJK input at this prompt:\033[0m\r\n"
            + "\033[32m>>> \033[0m\033[?2026l");
        terminal.onScreenUpdated();
        terminal.setBackgroundColor(session.getEmulator().getPalette()[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertFalse(session.getEmulator().isSynchronizedOutputActive());
        assertEquals(8, session.getEmulator().getRendererState().getCursorRow());
        assertEquals(4, session.getEmulator().getRendererState().getCursorCol());
        assertEquals(0, terminal.getTopRow());

        String draft = "한글 漢字 かな";
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        assertTrue(input.setComposingText(draft, 1));
        idle();
        if (optIn) assertEquals(draft, preview());
        else assertNull(preview());
        assertEquals("Preedit must remain uncommitted in both modes", "", received(session));
        assertFalse(session.getEmulator().getScreen().getTranscriptText().contains(draft));
        assertEquals(8, session.getEmulator().getRendererState().getCursorRow());
        assertEquals(4, session.getEmulator().getRendererState().getCursorCol());

        Bitmap bitmap = capture();
        try {
            File directory = new File("build/reports/tests/workbench");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            File image = new File(directory, "ime-preedit-" + (optIn ? "on" : "off") + ".png");
            try (FileOutputStream output = new FileOutputStream(image)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            return bitmap;
        } catch (Exception | AssertionError failure) {
            bitmap.recycle();
            throw failure;
        }
    }

    @Test public void offPreservesLegacyInputTypesFinishAndNoPreview() {
        EditorInfo attrs = new EditorInfo();
        InputConnection input = terminal.onCreateInputConnection(attrs);
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, attrs.inputType);
        input.setComposingText("world", 1);
        assertNull(preview());
        input.finishComposingText();
        assertEquals("world", received(session));
        input.commitText("\n", 1);
        assertEquals("\r", received(session));
        enforce = false;
        input = terminal.onCreateInputConnection(attrs = new EditorInfo());
        assertEquals(InputType.TYPE_NULL, attrs.inputType);
        input.setComposingText("legacy", 1);
        input.finishComposingText();
        assertEquals("legacy", received(session));
        assertNull(preview());
    }

    @Test public void offDoesNotAddLegacyImeRestartAndStaleHoneyboardCloseCannotFlushIntoNewSession() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, "com.example.ime/.Keyboard");
        InputConnection legacy = terminal.onCreateInputConnection(new EditorInfo());
        legacy.setComposingText("legacy draft", 1);
        RecordingImm.clear();
        terminal.attachSession(otherSession);
        idle();
        assertTrue("OFF non-Honeyboard input must not gain session-change restarts", RecordingImm.restarts.isEmpty());
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.samsung.android.honeyboard/.service.HoneyBoardService");
        InputConnection honeyboard = terminal.onCreateInputConnection(new EditorInfo());
        honeyboard.setComposingText("old Honeyboard draft", 1);
        terminal.attachSession(session);
        honeyboard.closeConnection();
        idle();
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void onUsesRealComposingSpansAndSelectionEvenWithHoneyboard() {
        enabled = true;
        EditorInfo attrs = new EditorInfo();
        InputConnection input = terminal.onCreateInputConnection(attrs);
        assertEquals(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            attrs.inputType);
        assertEquals(0, attrs.initialSelStart);
        assertEquals("", input.getTextAfterCursor(10, 0).toString());
        String composing = "한字😀";
        input.setComposingText(composing, 1);
        assertEquals(composing, preview());
        input.setSelection(1, 1);
        assertEquals(1, (int) ReflectionHelpers.getField(terminal, "mComposingCursor"));
        assertEquals("한", input.getTextBeforeCursor(10, 0).toString());
        assertEquals("字😀", input.getTextAfterCursor(10, 0).toString());
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.token = 19;
        ExtractedText text = input.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
        assertEquals(composing, text.text.toString());
        assertEquals(1, text.selectionStart);
        terminal.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT));
        assertEquals(composing, preview());
        assertEquals("", received(session));
        idle();
        assertEquals(0, RecordingImm.composingStart);
        assertEquals(composing.length(), RecordingImm.composingEnd);
        assertEquals(1, RecordingImm.selectionStart);
        assertEquals(composing, RecordingImm.extracted.text.toString());
        input.commitText("韓字", 1);
        assertEquals("韓字", received(session));
        assertNull(preview());
        input.finishComposingText();
        assertEquals("", received(session));
    }

    @Test public void composingRegionAndDeletionFollowEditableStateWithoutPtyBackspaces() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("abc", 1);
        input.setComposingRegion(1, 3);
        input.setSelection(2, 2);
        assertEquals("bc", preview());
        assertEquals(1, (int) ReflectionHelpers.getField(terminal, "mComposingCursor"));
        input.deleteSurroundingText(1, 0);
        assertEquals("bc", preview());
        assertEquals("", received(session));
        input.finishComposingText();
        assertEquals("bc", received(session));
        input.deleteSurroundingTextInCodePoints(1, 0);
        assertEquals("IMM key dispatch must remain asynchronous", "", received(session));
        idle();
        assertEquals("Code-point Backspace outside preedit must reach the terminal", "\u007f", received(session));
        input.commitText("😀", 1);
        assertEquals("😀", received(session));
        input.deleteSurroundingTextInCodePoints(1, 0);
        assertEquals("", received(session));
        idle();
        assertEquals("\u007f", received(session));
        input.setComposingText("", 1);
        assertNull(preview());
        input.finishComposingText();
        assertEquals("", received(session));
    }

    private void assertTerminalBackspace(InputConnection input, boolean codePoints) {
        assertTrue(codePoints ? input.deleteSurroundingTextInCodePoints(1, 0) : input.deleteSurroundingText(1, 0));
        assertEquals("IMM key dispatch must remain asynchronous", "", received(session));
        idle();
        assertEquals("\u007f", received(session));
        assertEquals("", received(otherSession));
    }

    private void assertCollapsedComposingRegionsCommitOnce() {
        enabled = true;
        int[][] regions = {{0, 0}, {1, 1}, {3, 3}, {-7, -1}, {8, 12}, {12, 8}};
        for (int[] region : regions) {
            for (boolean codePoints : new boolean[]{false, true}) {
                InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
                input.setComposingText("abc", 1);
                assertEquals("", received(session));
                assertTrue(input.setComposingRegion(region[0], region[1]));
                Editable editable = ((BaseInputConnection) input).getEditable();
                assertEquals("No invisible pending text may remain after a collapsed region", 0, editable.length());
                assertNull(preview());
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable));
                assertEquals(-1, BaseInputConnection.getComposingSpanEnd(editable));
                input.finishComposingText();
                terminal.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                assertTrue(codePoints ? input.deleteSurroundingTextInCodePoints(1, 0) : input.deleteSurroundingText(1, 0));
                assertEquals("Text must precede navigation, with no duplicate finish or early Backspace", "abc\033[D", received(session));
                idle();
                assertEquals("\u007f", received(session));
                input.finishComposingText();
                assertEquals("", received(session));
                input.commitText("next", 1);
                assertEquals("next", received(session));
                assertEquals("", received(otherSession));
            }
        }
    }

    @Test public void collapsedAndClippedComposingRegionsFinishPendingTextBeforeEitherBackspaceApi() {
        assertCollapsedComposingRegionsCommitOnce();
    }

    @Test public void ordinaryReversedComposingRegionsStayActiveAndEmptyRegionsDoNotSendText() {
        enabled = true;
        int[][] regions = {{0, 2}, {2, 0}, {1, 3}, {3, 1}, {5, -2}};
        String[] previews = {"ab", "ab", "bc", "bc", "abc"};
        for (int i = 0; i < regions.length; i++) {
            InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
            input.setComposingText("abc", 1);
            assertTrue(input.setComposingRegion(regions[i][0], regions[i][1]));
            assertEquals(previews[i], preview());
            assertEquals("abc", ((BaseInputConnection) input).getEditable().toString());
            assertEquals("", received(session));
            input.finishComposingText();
            assertEquals("abc", received(session));
            input.finishComposingText();
            assertTrue(input.setComposingRegion(regions[i][0], regions[i][1]));
            assertNull(preview());
            assertEquals(0, ((BaseInputConnection) input).getEditable().length());
            assertEquals("", received(session));
        }
    }

    private void assertPartialComposingTextCancellationCommitsRetainedText() {
        enabled = true;
        int[][] regions = {{0, 2}, {1, 2}, {1, 3}};
        String[] retained = {"c", "ac", "a"};
        for (int i = 0; i < regions.length; i++) {
            InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
            input.setComposingText("abc", 1);
            input.setComposingRegion(regions[i][0], regions[i][1]);
            input.setComposingText("", 1);
            assertEquals(retained[i], received(session));
            assertEquals(0, ((BaseInputConnection) input).getEditable().length());
            assertNull(preview());
            input.finishComposingText();
            assertEquals("", received(session));
            assertTerminalBackspace(input, true);
        }
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("abc", 1);
        input.setComposingText("", 1);
        input.finishComposingText();
        assertEquals("Replacing the entire draft with empty text remains cancellation", "", received(session));
    }

    @Test public void emptyReplacementOfAPartialCompositionDoesNotHideItsPendingPrefixOrSuffix() {
        assertPartialComposingTextCancellationCommitsRetainedText();
    }

    @Test public void collapsedRegionFinalizationPreservesOffAndStaleConnectionCancellation() {
        for (String ime : new String[]{"com.example.ime/.Keyboard", "com.samsung.android.honeyboard/.Ime"}) {
            Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, ime);
            InputConnection off = terminal.onCreateInputConnection(new EditorInfo());
            off.setComposingText("legacy", 1);
            off.setComposingRegion(1, 1);
            assertEquals("legacy", ((BaseInputConnection) off).getEditable().toString());
            assertEquals("", received(session));
            off.finishComposingText();
            assertEquals("legacy", received(session));
        }
        enabled = true;
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("cancelled", 1);
        terminal.attachSession(otherSession);
        assertFalse(old.setComposingRegion(0, 0));
        assertFalse(old.setComposingText("", 1));
        old.closeConnection();
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    private void assertEmptyComposingApisUseTerminalBackspace() {
        enabled = true;
        for (boolean emptyRegion : new boolean[]{false, true}) {
            for (boolean codePoints : new boolean[]{false, true}) {
                InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
                if (emptyRegion) {
                    assertTrue(input.setComposingRegion(0, 0));
                } else {
                    assertTrue(input.setComposingText("draft", 1));
                    assertTrue(input.setComposingText("", 1));
                }
                Editable editable = ((BaseInputConnection) input).getEditable();
                assertEquals(0, editable.length());
                // AOSP's default builder rejects zero-length EXCLUSIVE_EXCLUSIVE spans.
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable));
                assertEquals(-1, BaseInputConnection.getComposingSpanEnd(editable));
                assertNull(preview());
                assertTerminalBackspace(input, codePoints);
            }
        }
    }

    @Test public void emptyComposingTextAndRegionUseTerminalBackspaceForBothDeletionApis() {
        assertEmptyComposingApisUseTerminalBackspace();
    }

    private void assertSyntheticZeroLengthMarkerUsesTerminalBackspace() {
        enabled = true;
        for (boolean codePoints : new boolean[]{false, true}) {
            InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
            assertTrue(input.setComposingText("draft", 1));
            Editable editable = ((BaseInputConnection) input).getEditable();
            // Defensive synthetic state, not a claim that ordinary empty composing APIs create it.
            // Retain the real framework COMPOSING marker with flags that permit an empty span.
            for (Object span : editable.getSpans(0, editable.length(), Object.class)) {
                if ((editable.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0)
                    editable.setSpan(span, 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE | Spanned.SPAN_COMPOSING);
            }
            editable.clear();
            assertEquals(0, BaseInputConnection.getComposingSpanStart(editable));
            assertEquals(0, BaseInputConnection.getComposingSpanEnd(editable));
            assertTrue(input.setSelection(0, 0));
            assertNull(preview());
            assertTerminalBackspace(input, codePoints);
        }
    }

    @Test public void syntheticZeroLengthComposingMarkerDoesNotSuppressEitherTerminalBackspaceApi() {
        assertSyntheticZeroLengthMarkerUsesTerminalBackspace();
    }

    @Test
    @Config(sdk = 24, shadows = {TerminalImePasteTest.NoPty.class, RecordingImm.class, Api24LayoutPaint.class})
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void emptyPreeditDeletionAlsoUsesOnlyMinimumSdkInputApis() {
        assertEmptyComposingApisUseTerminalBackspace();
        assertSyntheticZeroLengthMarkerUsesTerminalBackspace();
        assertCollapsedComposingRegionsCommitOnce();
        assertPartialComposingTextCancellationCommitsRetainedText();
    }

    @Test public void activeComposingCodePointDeletionPreservesSurrogatesAndDoesNotSendPtyBackspaces() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("😀字", 1);
        input.setComposingRegion(2, 3);
        input.setSelection(3, 3);
        input.deleteSurroundingTextInCodePoints(1, 0);
        assertEquals("字", ((BaseInputConnection) input).getEditable().toString());
        assertEquals("字", preview());
        idle();
        assertEquals("", received(session));
        input.finishComposingText();
        assertEquals("字", received(session));
    }

    @Test public void offKeepsLegacyEmptyCompositionDeletionBehavior() {
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("", 1);
        assertTerminalBackspace(input, false);
        input.deleteSurroundingTextInCodePoints(1, 0);
        idle();
        assertEquals("", received(session));
    }

    @Test
    @Config(sdk = 24, shadows = {TerminalImePasteTest.NoPty.class, RecordingImm.class, Api24LayoutPaint.class})
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void composingModelUsesOnlyMinimumSdkInputApis() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("한글", 1);
        input.setSelection(1, 1);
        assertEquals("한글", preview());
        assertEquals("한", input.getTextBeforeCursor(10, 0).toString());
        input.finishComposingText();
        assertEquals("한글", received(session));
    }

    @Test public void negotiatedLiteralControlsPasteAndBypassNavigationCommitExactlyOnce() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        for (int flags : new int[]{1, 7}) {
            output("\033[>" + flags + "u\033[?2004h");
            input.setComposingText("한\t\n", 1);
            input.finishComposingText();
            assertEquals("한\t\r", received(session));
            input.commitText("\n", 1);
            assertEquals("\r", received(session));
            input.commitText("first\nsecond", 1);
            assertEquals("\033[200~first\rsecond\033[201~", received(session));
            input.setComposingText("가", 1);
            terminal.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals("가\033[D", received(session));
            input.finishComposingText();
            assertEquals("", received(session));
            terminal.onKeyDown(KeyEvent.KEYCODE_ENTER,
                new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON));
            assertEquals("\033[13;2u", received(session));
            output("\033[<u");
        }
    }

    @Test public void replacedClosedReattachedAndBackgroundConnectionsCannotReplayPreedit() {
        enabled = true;
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old", 1);
        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        assertFalse(old.commitText("late", 1));
        assertNull(preview());
        current.setComposingText("pane", 1);
        RecordingImm.clear();
        terminal.attachSession(otherSession);
        assertFalse(current.finishComposingText());
        idle();
        assertTrue("Replacing the session in an already-focused view must request a fresh served connection",
            RecordingImm.restarts.contains(terminal));
        // Robolectric records IMM.restartInput; supply the new connection Android would request.
        current = terminal.onCreateInputConnection(new EditorInfo());
        current.setComposingText("closed", 1);
        current.closeConnection();
        assertFalse(current.commitText("late", 1));
        current = terminal.onCreateInputConnection(new EditorInfo());
        current.setComposingText("background", 1);
        terminal.onWindowFocusChanged(false);
        assertFalse(current.finishComposingText());
        idle();
        assertNull(preview());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    private void assertWindowFocusRecovery(boolean composing) {
        enabled = composing;
        controller.windowFocusChanged(true);
        assertTrue(terminal.hasWindowFocus());
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());
        RecordingImm.clear();

        // Synthetic root-focus callbacks, not a notification-shade or full system-IMM test.
        // The controller updates real AttachInfo before dispatch and drains its posted work.
        controller.windowFocusChanged(false);
        assertFalse(terminal.hasWindowFocus());
        assertTrue(terminal.hasFocus());
        assertNull(preview());
        assertTrue(RecordingImm.restarts.isEmpty());
        terminal.refreshImeComposingPreference();
        assertTrue(RecordingImm.restarts.isEmpty());
        if (composing) assertFalse(old.commitText("late", 1));
        else assertEquals("", old.getTextAfterCursor(1, 0).toString());

        controller.windowFocusChanged(true);
        assertTrue(terminal.hasWindowFocus());
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(terminal, RecordingImm.restarts.get(0));
        assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));

        // Only after proving production requested restart, supply Android's replacement callback.
        // The recording shadow does not emulate system IMM rebinding or keyboard visibility policy.
        EditorInfo attrs = new EditorInfo();
        InputConnection current = terminal.onCreateInputConnection(attrs);
        old.closeConnection();
        assertEquals("", received(session));
        if (composing) {
            assertEquals(0, attrs.initialSelStart);
            current.setComposingText("new draft", 1);
            assertEquals("new draft", preview());
            current.finishComposingText();
            assertEquals("new draft", received(session));
        } else {
            assertEquals(1, attrs.initialSelStart);
            assertEquals("x", current.getTextBeforeCursor(1, 0).toString());
            assertEquals("x", current.getTextAfterCursor(1, 0).toString());
            current.commitText("new target", 1);
            assertEquals("new target", received(session));
        }
        assertTerminalBackspace(current, false);
    }

    @Test public void windowFocusRegainRequestsComposingRecoveryWithoutReplayingDraftOrShowingKeyboard() {
        assertWindowFocusRecovery(true);
    }

    @Test public void windowFocusRegainRequestsHoneyboardMetadataRecoveryWithoutShowingKeyboard() {
        assertWindowFocusRecovery(false);
    }

    @Test
    @Config(sdk = 24, shadows = {TerminalImePasteTest.NoPty.class, RecordingImm.class, Api24LayoutPaint.class})
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void windowFocusRecoveryAlsoUsesOnlyMinimumSdkInputApis() {
        assertWindowFocusRecovery(true);
        assertWindowFocusRecovery(false);
    }

    @Test public void windowFocusRecoveryDefersBackgroundAndNullTargetsAndKeepsFinalRapidSession() {
        enabled = true;
        controller.windowFocusChanged(true);
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        RecordingImm.clear();
        controller.windowFocusChanged(false);
        terminal.attachSession(null);
        controller.windowFocusChanged(true);
        assertNull(terminal.getCurrentSession());
        assertTrue(RecordingImm.restarts.isEmpty());

        controller.windowFocusChanged(false);
        terminal.attachSession(otherSession);
        terminal.attachSession(session);
        idle();
        assertFalse(terminal.hasWindowFocus());
        assertTrue(RecordingImm.restarts.isEmpty());
        assertFalse(old.commitText("late", 1));
        controller.windowFocusChanged(true);
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(terminal, RecordingImm.restarts.get(0));
        assertSame(session, terminal.getCurrentSession());
        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        old.closeConnection();
        current.commitText("final target", 1);
        assertEquals("final target", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void windowFocusRecoveryDoesNotStealNativeEditorOrPaneFocus() {
        enabled = true;
        controller.windowFocusChanged(true);
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        RecordingImm.clear();
        controller.windowFocusChanged(false);
        assertTrue(other.requestFocus());
        controller.windowFocusChanged(true);
        assertTrue(other.hasFocus());
        assertFalse(RecordingImm.restarts.contains(terminal));
        RecordingImm.clear();

        controller.windowFocusChanged(false);
        assertTrue(editor.requestFocus());
        InputConnection nativeInput = editor.onCreateInputConnection(new EditorInfo());
        nativeInput.setComposingText("native draft", 1);
        controller.windowFocusChanged(true);
        assertTrue(editor.hasFocus());
        assertTrue(RecordingImm.restarts.isEmpty());
        old.closeConnection();
        nativeInput.finishComposingText();
        assertEquals("native draft", editor.getText().toString());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void reattachedViewThatMissedWindowGainCanStillRebindAfterSessionNavigation() {
        enabled = true;
        controller.windowFocusChanged(true);
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        controller.windowFocusChanged(false);
        LinearLayout parent = (LinearLayout) terminal.getParent();
        int index = parent.indexOfChild(terminal);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) terminal.getLayoutParams();
        parent.removeView(terminal);
        controller.windowFocusChanged(true);
        parent.addView(terminal, index, params);
        assertTrue(terminal.requestFocus());
        assertTrue(terminal.hasWindowFocus());
        InputConnection reattached = terminal.onCreateInputConnection(new EditorInfo());
        reattached.setComposingText("reattached draft", 1);
        RecordingImm.clear();
        terminal.attachSession(otherSession);
        idle();
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(terminal, RecordingImm.restarts.get(0));
        old.closeConnection();
        reattached.closeConnection();
        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        current.commitText("reattached target", 1);
        assertEquals("", received(session));
        assertEquals("reattached target", received(otherSession));
    }

    @Test public void windowFocusRecoveryDoesNotAddNonHoneyboardLegacyRestart() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        controller.windowFocusChanged(true);
        InputConnection legacy = terminal.onCreateInputConnection(new EditorInfo());
        legacy.setComposingText("legacy draft", 1);
        RecordingImm.clear();
        controller.windowFocusChanged(false);
        controller.windowFocusChanged(true);
        assertTrue(RecordingImm.restarts.isEmpty());
        legacy.commitText("legacy commit", 1);
        assertEquals("legacy commit", received(session));
        assertNull(preview());
    }

    @Test public void deferredRestartCannotDiscardNewPreeditOnTheSameConnection() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("a", 1);
        terminal.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals("a\033[D", received(session));
        input.setComposingText("b", 1);
        RecordingImm.clear();
        idle();
        assertFalse(RecordingImm.restarts.contains(terminal));
        assertEquals("b", preview());
        input.finishComposingText();
        assertEquals("b", received(session));
    }

    @Test public void nullAndRapidSessionReplacementsRestartOnlyTheFinalFocusedDestination() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("not replayed", 1);
        RecordingImm.clear();
        terminal.attachSession(null);
        terminal.attachSession(otherSession);
        terminal.attachSession(session);
        idle();
        assertEquals(1, java.util.Collections.frequency(RecordingImm.restarts, terminal));
        assertSame(session, terminal.getCurrentSession());
        assertFalse(input.commitText("late", 1));
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void deferredRestartCannotDisruptPaneOrIndependentNativeEditorComposition() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("가", 1);
        terminal.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals("가\033[D", received(session));
        assertTrue(other.requestFocus());
        assertTrue(editor.requestFocus());
        RecordingImm.clear();
        idle();
        assertFalse(RecordingImm.restarts.contains(terminal));
        assertFalse(input.commitText("late", 1));
        InputConnection nativeInput = editor.onCreateInputConnection(new EditorInfo());
        nativeInput.setComposingText("native draft", 1);
        nativeInput.finishComposingText();
        assertEquals("native draft", editor.getText().toString());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void composingOverlayUsesCompletedCursorPaletteAndStyleDuringSynchronizedOutput() {
        enabled = true;
        InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
        input.setComposingText("漢字😀é", 1);
        input.setSelection(1, 1);
        Bitmap completed = capture();
        input.setSelection(2, 2);
        output("\033[?2026h\033[3;12H\033[7m\033]10;#00ff00\007\033]11;#ff0000\007");
        assertTrue(session.getEmulator().isSynchronizedOutputActive());
        input.setSelection(1, 1);
        Bitmap during = capture();
        assertTrue("IME redraw may move its own cursor, but must not expose live terminal state", completed.sameAs(during));
        output("\033[?2026l");
        Bitmap finished = capture();
        assertFalse(completed.sameAs(finished));
        assertEquals("", received(session));
        completed.recycle();
        during.recycle();
        finished.recycle();
    }

    @Test public void enablingPreferenceAfterLegacyWindowLossRequestsOnRebindOnReturn() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        controller.windowFocusChanged(true);
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("legacy draft", 1);
        RecordingImm.clear();
        controller.windowFocusChanged(false);
        enabled = true;
        terminal.refreshImeComposingPreference();
        assertTrue(RecordingImm.restarts.isEmpty());
        controller.windowFocusChanged(true);
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(terminal, RecordingImm.restarts.get(0));
        // Prove the restart request before supplying the framework's replacement callback.
        EditorInfo attrs = new EditorInfo();
        InputConnection current = terminal.onCreateInputConnection(attrs);
        old.closeConnection();
        assertEquals("", received(session));
        assertEquals(0, attrs.initialSelStart);
        current.setComposingText("new draft", 1);
        assertEquals("new draft", preview());
        current.finishComposingText();
        assertEquals("new draft", received(session));
    }

    @Test public void naturalOnReplacementBeforeQueuedRecoveryCancelsOnlyTheOldLegacyDraft() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        enabled = true;
        terminal.refreshImeComposingPreference();
        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        current.setComposingText("new draft", 1);
        old.closeConnection();
        idle();
        assertTrue("A naturally supplied ON connection supersedes the queued restart", RecordingImm.restarts.isEmpty());
        assertEquals("", received(session));
        assertEquals("new draft", preview());
        current.finishComposingText();
        assertEquals("new draft", received(session));
    }

    @Test public void queuedPreferenceRefreshCoalescesAndRechecksAnOffRoundTrip() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        InputConnection legacy = terminal.onCreateInputConnection(new EditorInfo());
        legacy.setComposingText("retained legacy", 1);
        enabled = true;
        terminal.refreshImeComposingPreference();
        terminal.refreshImeComposingPreference();
        enabled = false;
        idle();
        assertTrue("A cancelled enable must not manufacture an ordinary OFF restart", RecordingImm.restarts.isEmpty());
        legacy.closeConnection();
        assertEquals("No admitted enable means legacy closure is unchanged", "retained legacy", received(session));
        terminal.onCreateInputConnection(new EditorInfo());
        enabled = true;
        terminal.refreshImeComposingPreference();
        terminal.refreshImeComposingPreference();
        assertTrue(RecordingImm.restarts.isEmpty());
        idle();
        assertEquals(1, RecordingImm.restarts.size());
    }

    @Test public void preferenceRecoveryFollowsFinalSessionAndDoesNotStealANativeEditor() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old draft", 1);
        enabled = true;
        terminal.refreshImeComposingPreference();
        terminal.attachSession(null);
        terminal.refreshImeComposingPreference();
        terminal.attachSession(otherSession);
        terminal.refreshImeComposingPreference();
        terminal.refreshImeComposingPreference();
        idle();
        assertEquals(1, RecordingImm.restarts.size());
        assertSame(otherSession, terminal.getCurrentSession());
        old.closeConnection();
        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        current.setComposingText("new draft", 1);
        RecordingImm.clear();
        enabled = false;
        terminal.refreshImeComposingPreference();
        assertTrue(editor.requestFocus());
        InputConnection nativeInput = editor.onCreateInputConnection(new EditorInfo());
        nativeInput.setComposingText("native draft", 1);
        idle();
        assertTrue(RecordingImm.restarts.isEmpty());
        assertTrue(editor.hasFocus());
        assertEquals("native draft", editor.getText().toString());
        assertEquals("", received(session));
        assertEquals("", received(otherSession));
    }

    @Test public void enablingPreferenceWhileTheWindowHasNoSessionWaitsForARealTarget() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        controller.windowFocusChanged(true);
        terminal.onCreateInputConnection(new EditorInfo());
        controller.windowFocusChanged(false);
        terminal.attachSession(null);
        enabled = true;
        terminal.refreshImeComposingPreference();
        controller.windowFocusChanged(true);
        assertTrue(RecordingImm.restarts.isEmpty());
        terminal.attachSession(otherSession);
        idle();
        assertEquals(1, RecordingImm.restarts.size());
        InputConnection current = terminal.onCreateInputConnection(new EditorInfo());
        current.setComposingText("on draft", 1);
        RecordingImm.clear();
        controller.windowFocusChanged(false);
        enabled = false;
        terminal.refreshImeComposingPreference();
        assertTrue(RecordingImm.restarts.isEmpty());
        controller.windowFocusChanged(true);
        assertEquals(1, RecordingImm.restarts.size());
        current.closeConnection();
        InputConnection off = terminal.onCreateInputConnection(new EditorInfo());
        off.setComposingText("off draft", 1);
        assertNull(preview());
        off.finishComposingText();
        assertEquals("off draft", received(otherSession));
        assertEquals("", received(session));
    }

    @Test public void admittedOnRebindCancelsOldLegacyDraftEvenAfterPreferenceReturnsOff() {
        Settings.Secure.putString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.ime/.Keyboard");
        controller.windowFocusChanged(true);
        InputConnection old = terminal.onCreateInputConnection(new EditorInfo());
        old.setComposingText("old legacy draft", 1);
        controller.windowFocusChanged(false);
        enabled = true;
        controller.windowFocusChanged(true);
        assertEquals(1, RecordingImm.restarts.size());
        enabled = false;
        InputConnection off = terminal.onCreateInputConnection(new EditorInfo());
        old.closeConnection();
        assertEquals("", received(session));
        off.setComposingText("current OFF draft", 1);
        off.closeConnection();
        assertEquals("current OFF draft", received(session));
    }

    @Test public void preferenceDefaultsOffAndRefreshDoesNotStealEditorFocus() throws Exception {
        // build() opens this XML in PRIVATE and MULTI_PROCESS modes. Complete the first
        // private load before that dual-mode construction, so API28 cannot start both
        // initial and reload threads before either publishes its map.
        Context packageContext = activity.createPackageContext(TermuxConstants.TERMUX_PACKAGE_NAME,
            Context.CONTEXT_RESTRICTED);
        assertEquals(TermuxConstants.TERMUX_PACKAGE_NAME, packageContext.getPackageName());
        SharedPreferences primed = packageContext.getSharedPreferences(
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION, Context.MODE_PRIVATE);
        assertTrue("The isolated preference fixture must start empty", primed.getAll().isEmpty());
        assertFalse(primed.contains(TERMUX_APP.KEY_IME_COMPOSING_ENABLED));
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity, true);
        assertNotNull(preferences);
        assertSame("The initial-load barrier must cover the production preference backend",
            primed, preferences.getSharedPreferences());
        assertFalse(preferences.isImeComposingEnabled());
        preferences.setImeComposingEnabled(true);
        assertTrue(preferences.isImeComposingEnabled());
        preferences.setImeComposingEnabled(false);
        assertFalse(preferences.isImeComposingEnabled());
        terminal.onCreateInputConnection(new EditorInfo());
        enabled = true;
        terminal.refreshImeComposingPreference();
        idle();
        assertTrue(RecordingImm.restarts.contains(terminal));
        terminal.onCreateInputConnection(new EditorInfo());
        assertTrue(editor.requestFocus());
        RecordingImm.clear();
        enabled = false;
        terminal.refreshImeComposingPreference();
        assertTrue(editor.hasFocus());
        assertTrue(RecordingImm.restarts.isEmpty());
    }
}
