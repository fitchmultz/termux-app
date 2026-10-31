package com.termux.app.terminal;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, application = EvidenceDockTest.TestApplication.class,
    qualifiers = "w900dp-h1000dp-night-mdpi", shadows = TerminalPaneLayoutTest.NoPty.class)
public class SystemPaletteTest {
    private TerminalSession session(String name, TermuxShellManager manager) {
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession terminal = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(terminal, "mEmulator", new TerminalEmulator(terminal, 80, 24, 8, 16, 100, client));
        ReflectionHelpers.setField(terminal, "mShellPid", Integer.MAX_VALUE);
        terminal.mSessionName = name;
        ExecutionCommand command = new ExecutionCommand(1, "/unused", new String[0], null, "/",
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(), true);
        TermuxSession session = ReflectionHelpers.callConstructor(TermuxSession.class,
            ClassParameter.from(TerminalSession.class, terminal),
            ClassParameter.from(ExecutionCommand.class, command),
            ClassParameter.from(TermuxSession.TermuxSessionClient.class, null),
            ClassParameter.from(boolean.class, false));
        manager.mTermuxSessions.add(session);
        byte[] output = ("\033[?2031h" + name + "\r\nReady for your next command.\r\n").getBytes(StandardCharsets.UTF_8);
        terminal.getEmulator().append(output, output.length);
        return terminal;
    }

    private String input(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[128];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    @Test
    public void systemAppearanceUpdatesVisibleAndHiddenSessionsWithoutLosingDrafts() throws Exception {
        Properties dark = new Properties();
        dark.setProperty("background", "#000000");
        dark.setProperty("foreground", "#ffffff");
        dark.setProperty("cursor", "#ffffff");
        TerminalColors.COLOR_SCHEME.updateWith(dark);
        Application app = RuntimeEnvironment.getApplication();
        TermuxShellManager manager = TermuxShellManager.init(app);
        manager.mTermuxSessions.clear();
        TerminalSession first = session("first", manager);
        TerminalSession second = session("second", manager);
        TerminalSession hidden = session("hidden", manager);
        TermuxAppSharedProperties.init(app);
        TermuxService service = Robolectric.buildService(TermuxService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            new ComponentName(app, TermuxService.class), service.onBind(new Intent(app, TermuxService.class)));
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class).setup();
        try {
            TermuxActivity activity = controller.get();
            activity.getTerminalPanes().showSession(first);
            activity.getTerminalPanes().openBeside(second);
            activity.showEvidenceDock();
            EditText editor = activity.findViewById(R.id.terminal_toolbar_stacked_text_input);
            editor.setText("Unsent draft");
            TerminalPaneLayoutTest.size(activity.findViewById(R.id.activity_termux_root_view), 900, 650);
            TerminalPaneLayoutTest.capture(activity.findViewById(R.id.activity_termux_root_view), "palette-dark");
            assertEquals(0xff000000, first.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);

            RuntimeEnvironment.setQualifiers("+notnight");
            controller.configurationChange();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            activity = controller.get();
            assertSame(second, activity.getTerminalView().getCurrentSession());
            assertEquals("Unsent draft", ((EditText) activity.findViewById(R.id.terminal_toolbar_stacked_text_input)).getText().toString());
            for (TerminalSession terminal : new TerminalSession[]{first, second, hidden}) {
                assertEquals(0xffeff1f5, terminal.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
                assertEquals(0xff4c4f69, terminal.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND]);
                assertEquals("\033[?997;2n", input(terminal));
            }
            TerminalPaneLayoutTest.size(activity.findViewById(R.id.activity_termux_root_view), 900, 650);
            TerminalPaneLayoutTest.capture(activity.findViewById(R.id.activity_termux_root_view), "palette-light");

            RuntimeEnvironment.setQualifiers("+night");
            controller.configurationChange();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            for (TerminalSession terminal : new TerminalSession[]{first, second, hidden}) {
                assertEquals(0xff000000, terminal.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
                assertEquals("\033[?997;1n", input(terminal));
            }
        } finally {
            controller.pause().stop().destroy();
            manager.mTermuxSessions.clear();
        }
    }
}
