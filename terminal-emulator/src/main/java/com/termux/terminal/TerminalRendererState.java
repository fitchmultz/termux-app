package com.termux.terminal;

/**
 * Read-only terminal presentation contract consumed by terminal renderers.
 *
 * <p>The live {@link TerminalEmulator} normally implements this contract. While DEC synchronized
 * output mode 2026 is active, the emulator instead exposes an immutable copy of the last completed
 * visible screen so a framework-forced draw cannot reveal parser-in-progress state.</p>
 */
public interface TerminalRendererState {

    int getRows();

    int getColumns();

    int getCursorRow();

    int getCursorCol();

    int getCursorStyle();

    boolean isReverseVideo();

    boolean shouldCursorBeVisible();

    TerminalBuffer getScreen();

    int[] getPalette();
}
