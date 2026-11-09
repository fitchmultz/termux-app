package com.termux.terminal;

import junit.framework.TestCase;

/** Exercises the real final buffer directly, without test-only inheritance or timing policy. */
public class WordLookupTest extends TestCase {
    public void testLongWrappedTranscriptWordsAreCompleteFromEitherDirection() {
        int columns = 80, screenRows = 50, historyRows = 2000;
        TerminalBuffer buffer = new TerminalBuffer(columns, historyRows + screenRows, screenRows);
        for (int row = 0; row < screenRows; row++) {
            buffer.blockSet(0, row, columns, 1, 'x', TextStyle.NORMAL);
            buffer.setLineWrap(row);
        }
        for (int row = 0; row < historyRows; row++) {
            buffer.scrollDownOneLine(0, screenRows, TextStyle.NORMAL);
            buffer.blockSet(0, screenRows - 1, columns, 1, 'x', TextStyle.NORMAL);
            buffer.setLineWrap(screenRows - 1);
        }
        assertEquals(historyRows, buffer.getActiveTranscriptRows());
        String expected = "x".repeat((historyRows + screenRows) * columns);
        // Visible tap expands upward; a scrolled-back tap expands downward. The production
        // implementation checks each adjacent boundary once, then serializes this full word.
        for (int tappedRow : new int[]{25, -historyRows + 10})
            assertEquals(expected, buffer.getWordAtLocation(40, tappedRow));
    }
}
