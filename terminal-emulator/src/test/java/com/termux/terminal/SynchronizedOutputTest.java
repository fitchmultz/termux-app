package com.termux.terminal;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;

public class SynchronizedOutputTest extends TestCase {

    private static final int COLUMNS = 4;
    private static final int ROWS = 2;

    private MockTerminalSessionClient mClient;
    private TerminalSession mSession;
    private TerminalEmulator mEmulator;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mClient = new MockTerminalSessionClient();
        mSession = new TerminalSession("", "", new String[0], new String[0], ROWS * 2, mClient);
        mEmulator = new TerminalEmulator(
            mSession,
            COLUMNS,
            ROWS,
            TerminalTestCase.INITIAL_CELL_WIDTH_PIXELS,
            TerminalTestCase.INITIAL_CELL_HEIGHT_PIXELS,
            ROWS * 2,
            mClient
        );
        mSession.mEmulator = mEmulator;
        mClient.resetCounts();
    }

    public void testScreenNotificationsAreDeferredUntilFrameEnds() {
        append("\033[?2026habc");
        mSession.notifyScreenUpdate();
        assertTrue(mEmulator.isSynchronizedOutputActive());
        assertEquals(0, mClient.textChanged);

        append("d");
        mSession.notifyScreenUpdate();
        assertEquals(0, mClient.textChanged);

        append("\033[?2026l");
        mSession.notifyScreenUpdate();
        assertFalse(mEmulator.isSynchronizedOutputActive());
        assertEquals(1, mClient.textChanged);
    }

    public void testRepeatedSetDoesNotPresentFrameEarly() {
        append("\033[?2026habc");
        mSession.notifyScreenUpdate();

        append("\033[?2026hdef");
        mSession.notifyScreenUpdate();
        assertTrue(mEmulator.isSynchronizedOutputActive());
        assertEquals(0, mClient.textChanged);

        append("\033[?2026l");
        mSession.notifyScreenUpdate();
        assertFalse(mEmulator.isSynchronizedOutputActive());
        assertEquals(1, mClient.textChanged);
    }

    public void testWatchdogPresentsAndEndsAbandonedFrame() {
        append("\033[?2026habc");
        mSession.notifyScreenUpdate();
        assertEquals(0, mClient.textChanged);
        assertNotSame(mEmulator, mEmulator.getRendererState());

        mSession.handleSynchronizedOutputTimeout();
        assertFalse(mEmulator.isSynchronizedOutputActive());
        assertSame(mEmulator, mEmulator.getRendererState());
        assertEquals(1, mClient.textChanged);
    }

    public void testRendererRetainsCompletedVisibleScreenDuringFrame() {
        append("old");
        append("\033[?2026h");
        TerminalRendererState completed = mEmulator.getRendererState();
        assertNotSame(mEmulator, completed);
        assertEquals("old", firstCompletedLine(completed));
        assertEquals(0, completed.getScreen().getActiveTranscriptRows());
        assertEquals(ROWS, completed.getScreen().mTotalRows);

        append("\rNEW");
        assertEquals("NEW", firstCompletedLine(mEmulator));
        assertEquals("old", firstCompletedLine(mEmulator.getRendererState()));

        append("\033[?2026l");
        assertSame(mEmulator, mEmulator.getRendererState());
        assertEquals("NEW", firstCompletedLine(mEmulator.getRendererState()));
    }

    public void testRepeatedSetRetainsOriginalCompletedSnapshot() {
        append("old\033[?2026h");
        TerminalRendererState completed = mEmulator.getRendererState();
        append("\rNEW\033[?2026h");

        assertSame(completed, mEmulator.getRendererState());
        assertEquals("old", firstCompletedLine(completed));
    }

    public void testRendererSnapshotCopiesPaletteAndResetReleasesIt() {
        append("\033[?2026h");
        TerminalRendererState completed = mEmulator.getRendererState();
        int completedColor = completed.getPalette()[0];
        mEmulator.mColors.mCurrentColors[0] ^= 0x00ffffff;

        assertEquals(completedColor, completed.getPalette()[0]);
        assertTrue(completedColor != mEmulator.getPalette()[0]);
        mEmulator.reset();
        assertSame(mEmulator, mEmulator.getRendererState());
    }

    public void testVisualCallbacksAreDeferredWithText() {
        append("\033[?2026h");
        mEmulator.doDecSetOrReset(false, 25);
        mSession.onColorsChanged();
        mSession.notifyScreenUpdate();

        assertEquals(0, mClient.cursorStateChanged);
        assertEquals(0, mClient.colorsChanged);
        assertEquals(0, mClient.textChanged);

        append("\033[?2026l");
        mSession.notifyScreenUpdate();

        assertEquals(1, mClient.cursorStateChanged);
        assertFalse(mClient.lastCursorState);
        assertEquals(1, mClient.colorsChanged);
        assertEquals(1, mClient.textChanged);
    }

    private String firstCompletedLine(TerminalRendererState state) {
        return state.getScreen().getSelectedText(0, 0, COLUMNS - 1, 0);
    }

    private void append(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        mEmulator.append(bytes, bytes.length);
    }

    private static final class MockTerminalSessionClient implements TerminalSessionClient {
        int textChanged;
        int colorsChanged;
        int cursorStateChanged;
        boolean lastCursorState;

        void resetCounts() {
            textChanged = 0;
            colorsChanged = 0;
            cursorStateChanged = 0;
            lastCursorState = true;
        }

        @Override
        public void onTextChanged(TerminalSession changedSession) {
            textChanged++;
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
            colorsChanged++;
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
            cursorStateChanged++;
            lastCursorState = state;
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
        }

        @Override
        public void logWarn(String tag, String message) {
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
        }
    }
}
