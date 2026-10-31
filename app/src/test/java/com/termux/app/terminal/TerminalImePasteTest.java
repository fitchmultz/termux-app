package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = Application.class, shadows = TerminalImePasteTest.NoPty.class)
public class TerminalImePasteTest {
    @Implements(className = "com.termux.terminal.JNI", isInAndroidSdk = false)
    public static class NoPty {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void setPtyWindowSize(int fd, int rows, int columns, int width, int height) {}
    }

    private TerminalSession session;
    private TerminalView terminal;
    private InputConnection input;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TermuxTerminalSessionClientBase sessionClient = new TermuxTerminalSessionClientBase();
        session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, sessionClient);
        ReflectionHelpers.setField(session, "mEmulator", new TerminalEmulator(session, 80, 24, 8, 16, 100, sessionClient));
        ReflectionHelpers.setField(session, "mShellPid", 1234);
        terminal = new TerminalView(activity, null);
        terminal.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override public boolean shouldEnforceCharBasedInput() { return true; }
        });
        terminal.setTextSize(16);
        activity.setContentView(terminal);
        terminal.attachSession(session);
        terminal.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        terminal.layout(0, 0, 900, 1000);
        input = terminal.onCreateInputConnection(new EditorInfo());
    }

    private void output(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(bytes, bytes.length);
    }

    private String received() {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    @Test
    public void multilineImeCommitUsesNativePasteWhenEnabledAndRetainsLegacyInputOtherwise() {
        output("\033[?2004h");
        assertTrue(session.getEmulator().isBracketedPasteModeEnabled());
        input.commitText("First line\nSecond line\n", 1);
        assertEquals("\033[200~First line\rSecond line\r\033[201~", received());
        input.commitText("First line\r\nSecond line", 1);
        assertEquals("\033[200~First line\rSecond line\033[201~", received());

        output("\033[?2004l");
        input.commitText("First line\nSecond line", 1);
        assertEquals("First line\rSecond line", received());
    }

    @Test
    public void typingCompositionAndSingleReturnKeepTheirExistingInput() {
        output("\033[?2004h");
        input.commitText("hello", 1);
        assertEquals("hello", received());
        input.setComposingText("world", 1);
        input.finishComposingText();
        assertEquals("world", received());
        input.commitText("\n", 1);
        assertEquals("\r", received());
        terminal.onKeyDown(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        assertEquals("\r", received());
        terminal.onKeyDown(KeyEvent.KEYCODE_J,
            new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_J, 0, KeyEvent.META_CTRL_ON));
        assertEquals("\n", received());
    }
}
