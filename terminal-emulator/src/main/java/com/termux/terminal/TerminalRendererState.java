package com.termux.terminal;

/**
 * Read-only terminal presentation contract consumed by terminal renderers.
 *
 * <p>The live {@link TerminalEmulator} normally implements this contract. While DEC synchronized
 * output mode 2026 is active, the emulator instead exposes a frozen copy of the last completed
 * visible screen so a framework-forced draw cannot reveal parser-in-progress state. Read-only is
 * a caller obligation: the returned buffer and array do not enforce immutability.</p>
 */
public interface TerminalRendererState {

    int getRows();

    int getColumns();

    int getCursorRow();

    int getCursorCol();

    int getCursorStyle();

    boolean isReverseVideo();

    boolean shouldCursorBeVisible();

    /**
     * Borrows this state's screen without transferring ownership.
     *
     * <p>Renderers read it on the session's main thread and must not mutate it. A live emulator's
     * buffer changes with parsing, resize, and screen switches; reacquire it for each render pass.
     * The completed synchronized-output state retains copied rows but shares published image pixels.
     * Retaining the buffer does not make live data a snapshot. A caller needing stable text beyond
     * the pass must copy the required text while on the owning thread.</p>
     */
    TerminalBuffer getScreen();

    /**
     * Borrows the state's color array. Callers must not modify it.
     *
     * <p>Read it with the screen on the session's main thread. The live palette changes as the
     * emulator processes input; a completed synchronized-output state has its own palette copy.
     * Copy the array only when independent, stable colors are needed beyond the render pass.</p>
     */
    int[] getPalette();
}
